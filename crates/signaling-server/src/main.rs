// UpDesk signaling server (Rust port).
//
// Byte-for-byte the same JSON/WebSocket protocol as the original Node server,
// so existing clients (and scripts/testClient.js) work against it unchanged.
// In-memory state, Ed25519 challenge/response auth, TOFU enrollment.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use ed25519_dalek::{Signature, VerifyingKey};
use futures_util::{SinkExt, StreamExt};
use rand::RngCore;
use serde_json::{json, Value};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpListener;
use tokio::sync::mpsc::{unbounded_channel, UnboundedSender};
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message;

const CHALLENGE_TTL: Duration = Duration::from_secs(30);

// ---------- shared state ----------

struct EnrollCode {
    kind: String,
    used: bool,
}

struct EnrolledKey {
    public_key: String,
    kind: String,
}

#[derive(Clone)]
struct Session {
    session_id: String,
    controller_id: String,
    device_id: String,
    status: String, // pending | active | ended | rejected
    start_time: u128,
    end_time: Option<u128>,
}

#[derive(Default)]
struct AppState {
    keys: Mutex<HashMap<String, EnrolledKey>>,
    enroll_codes: Mutex<HashMap<String, EnrollCode>>,
    devices: Mutex<HashMap<String, Value>>, // deviceId -> metadata
    device_numeric: Mutex<HashMap<String, String>>, // 9-digit connect ID -> deviceId (online only)
    sessions: Mutex<HashMap<String, Session>>,
    peers: Mutex<HashMap<String, UnboundedSender<Message>>>, // identityId -> writer
    db: Option<sqlx::PgPool>, // durable persistence + audit, when DATABASE_URL is set
}

impl AppState {
    fn new(db: Option<sqlx::PgPool>) -> Self {
        let state = AppState { db, ..Default::default() };
        state.load_enroll_codes_from_env();
        state
    }

    // ENROLL_CODES="ABCD-1234:device,WXYZ-9876:controller"
    fn load_enroll_codes_from_env(&self) {
        let raw = std::env::var("ENROLL_CODES").unwrap_or_default();
        let mut codes = self.enroll_codes.lock().unwrap();
        for entry in raw.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            match entry.split_once(':') {
                Some((code, kind)) if kind == "device" || kind == "controller" => {
                    codes.insert(code.to_string(), EnrollCode { kind: kind.to_string(), used: false });
                }
                _ => eprintln!("Ignoring malformed ENROLL_CODES entry: \"{entry}\""),
            }
        }
        println!("Loaded {} enroll code(s) from env", codes.len());
    }
}

// ---------- per-connection state ----------

struct Pending {
    identity_id: String,
    kind: String,
    public_key: String,
    nonce: String,
    is_enrollment: bool,
    enroll_code: Option<String>,
    expires_at: Instant,
}

#[derive(Clone)]
struct Identity {
    id: String,
    kind: String,
}

#[derive(Default)]
struct ConnState {
    pending: Option<Pending>,
    identity: Option<Identity>,
    registered_device_id: Option<String>,
    is_admin: bool,
}

// ---------- crypto ----------

// Verify an Ed25519 signature over `message`. `public_key_b64` is base64 SPKI
// DER (as Node exports); the raw 32-byte key is its final 32 bytes.
fn verify_sig(public_key_b64: &str, message: &[u8], signature_b64: &str) -> bool {
    let der = match B64.decode(public_key_b64) {
        Ok(d) if d.len() >= 32 => d,
        _ => return false,
    };
    let raw: [u8; 32] = match der[der.len() - 32..].try_into() {
        Ok(r) => r,
        Err(_) => return false,
    };
    let vk = match VerifyingKey::from_bytes(&raw) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let sig_bytes = match B64.decode(signature_b64) {
        Ok(s) if s.len() == 64 => s,
        _ => return false,
    };
    let sig = Signature::from_bytes(&sig_bytes[..].try_into().unwrap());
    vk.verify_strict(message, &sig).is_ok()
}

fn now_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis()
}

fn random_nonce() -> String {
    let mut b = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut b);
    B64.encode(b)
}

// ---------- send helpers ----------

fn send_json(tx: &UnboundedSender<Message>, v: Value) {
    let _ = tx.send(Message::Text(v.to_string()));
}

fn send_peer(state: &Arc<AppState>, id: &str, v: Value) -> bool {
    let peers = state.peers.lock().unwrap();
    if let Some(tx) = peers.get(id) {
        let _ = tx.send(Message::Text(v.to_string()));
        true
    } else {
        false
    }
}

// ---------- main ----------

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();
    let _ = tokio_rustls::rustls::crypto::ring::default_provider().install_default();
    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".to_string());

    let db = connect_db().await;
    let state = Arc::new(AppState::new(db.clone()));
    if let Some(pool) = &db {
        init_db(pool).await;
        load_keys(pool, &state).await;
    }

    let listener = TcpListener::bind(format!("0.0.0.0:{port}"))
        .await
        .expect("bind failed");
    let tls = load_tls();
    println!(
        "Signaling server (Rust) running on port {port} ({})",
        if tls.is_some() { "wss/TLS" } else { "ws" }
    );

    while let Ok((tcp, _)) = listener.accept().await {
        let state = state.clone();
        match &tls {
            Some(acceptor) => {
                let acceptor = acceptor.clone();
                tokio::spawn(async move {
                    match acceptor.accept(tcp).await {
                        Ok(tls_stream) => handle_conn(tls_stream, state).await,
                        Err(e) => eprintln!("TLS handshake failed: {e}"),
                    }
                });
            }
            None => {
                tokio::spawn(handle_conn(tcp, state));
            }
        }
    }
}

// Enables wss:// when TLS_CERT + TLS_KEY (PEM paths) are set; otherwise plain
// ws://. Uses rustls (ring) — it negotiates cleanly with Chromium/WebView2,
// unlike SChannel-as-a-server.
fn load_tls() -> Option<Arc<tokio_rustls::TlsAcceptor>> {
    use std::fs::File;
    use std::io::BufReader;
    let cert_path = std::env::var("TLS_CERT").ok()?;
    let key_path = std::env::var("TLS_KEY").ok()?;
    let certs = rustls_pemfile::certs(&mut BufReader::new(
        File::open(&cert_path).unwrap_or_else(|e| panic!("cannot open TLS_CERT {cert_path}: {e}")),
    ))
    .collect::<Result<Vec<_>, _>>()
    .expect("parse TLS_CERT");
    let key = rustls_pemfile::private_key(&mut BufReader::new(
        File::open(&key_path).unwrap_or_else(|e| panic!("cannot open TLS_KEY {key_path}: {e}")),
    ))
    .expect("read TLS_KEY")
    .expect("no private key found in TLS_KEY");
    let config = tokio_rustls::rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .expect("build rustls server config");
    Some(Arc::new(tokio_rustls::TlsAcceptor::from(Arc::new(config))))
}

// ---------- persistence (optional) ----------

const CREATE_ENROLLED: &str = "CREATE TABLE IF NOT EXISTS enrolled_keys (\
    identity_id TEXT PRIMARY KEY, public_key TEXT NOT NULL, kind TEXT NOT NULL, \
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now())";
const CREATE_SESSIONS: &str = "CREATE TABLE IF NOT EXISTS sessions (\
    session_id TEXT PRIMARY KEY, controller_id TEXT NOT NULL, device_id TEXT NOT NULL, \
    status TEXT NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT, duration_ms BIGINT)";

async fn connect_db() -> Option<sqlx::PgPool> {
    let url = std::env::var("DATABASE_URL").ok()?;
    let pool = sqlx::postgres::PgPoolOptions::new()
        .max_connections(5)
        .connect(&url)
        .await
        .expect("DATABASE_URL set but Postgres connection failed");
    println!("Connected to Postgres — persistence + audit enabled");
    Some(pool)
}

async fn init_db(pool: &sqlx::PgPool) {
    sqlx::query(CREATE_ENROLLED).execute(pool).await.expect("create enrolled_keys");
    sqlx::query(CREATE_SESSIONS).execute(pool).await.expect("create sessions");
}

async fn load_keys(pool: &sqlx::PgPool, state: &AppState) {
    let rows = sqlx::query_as::<_, (String, String, String)>(
        "SELECT identity_id, public_key, kind FROM enrolled_keys",
    )
    .fetch_all(pool)
    .await
    .expect("load enrolled_keys");
    let mut keys = state.keys.lock().unwrap();
    for (identity_id, public_key, kind) in rows {
        keys.insert(identity_id, EnrolledKey { public_key, kind });
    }
    println!("Loaded {} enrolled key(s) from Postgres", keys.len());
}

// Fire-and-forget upsert of a terminal session into the audit log.
fn audit_session(state: &Arc<AppState>, s: &Session) {
    let pool = match &state.db {
        Some(p) => p.clone(),
        None => return,
    };
    let sid = s.session_id.clone();
    let cid = s.controller_id.clone();
    let did = s.device_id.clone();
    let status = s.status.clone();
    let start = s.start_time as i64;
    let end = s.end_time.map(|e| e as i64);
    let duration = end.map(|e| e - start);
    tokio::spawn(async move {
        let _ = sqlx::query(
            "INSERT INTO sessions (session_id, controller_id, device_id, status, start_ms, end_ms, duration_ms) \
             VALUES ($1,$2,$3,$4,$5,$6,$7) \
             ON CONFLICT (session_id) DO UPDATE SET \
               status = EXCLUDED.status, end_ms = EXCLUDED.end_ms, duration_ms = EXCLUDED.duration_ms",
        )
        .bind(sid)
        .bind(cid)
        .bind(did)
        .bind(status)
        .bind(start)
        .bind(end)
        .bind(duration)
        .execute(&pool)
        .await;
    });
}

async fn handle_conn<S>(stream: S, state: Arc<AppState>)
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let ws = match accept_async(stream).await {
        Ok(w) => w,
        Err(_) => return,
    };
    let (mut sink, mut incoming) = ws.split();
    let (tx, mut rx) = unbounded_channel::<Message>();

    // Writer task: drains the channel to the socket.
    let writer = tokio::spawn(async move {
        while let Some(m) = rx.recv().await {
            if sink.send(m).await.is_err() {
                break;
            }
        }
    });

    let mut conn = ConnState::default();
    while let Some(msg) = incoming.next().await {
        match msg {
            Ok(Message::Text(t)) => handle_text(&state, &mut conn, &tx, &t),
            Ok(Message::Close(_)) | Err(_) => break,
            _ => {}
        }
    }

    cleanup(&state, &conn);
    drop(tx);
    let _ = writer.await;
}

fn cleanup(state: &Arc<AppState>, conn: &ConnState) {
    if let Some(dev) = &conn.registered_device_id {
        state.devices.lock().unwrap().remove(dev);
        state.device_numeric.lock().unwrap().remove(&numeric_id(dev));
        println!("Device unregistered: {dev}");
    }
    if let Some(id) = &conn.identity {
        let mut ended: Vec<(Session, String)> = Vec::new();
        {
            let mut sessions = state.sessions.lock().unwrap();
            for s in sessions.values_mut() {
                let is_participant = s.controller_id == id.id || s.device_id == id.id;
                if is_participant && (s.status == "pending" || s.status == "active") {
                    s.status = "ended".to_string();
                    s.end_time = Some(now_ms());
                    let counterpart = if s.controller_id == id.id {
                        s.device_id.clone()
                    } else {
                        s.controller_id.clone()
                    };
                    ended.push((s.clone(), counterpart));
                }
            }
        }
        for (s, counterpart) in ended {
            audit_session(state, &s);
            send_peer(state, &counterpart, json!({ "type": "peer_disconnected", "sessionId": s.session_id }));
        }
        state.peers.lock().unwrap().remove(&id.id);
    }
}

// ---------- message handling ----------

fn random_code() -> String {
    let mut b = [0u8; 4];
    rand::thread_rng().fill_bytes(&mut b);
    let hex: String = b.iter().map(|x| format!("{x:02X}")).collect();
    format!("{}-{}", &hex[0..4], &hex[4..8])
}

// Stable 9-digit connect ID derived from a device's identity id (like an AnyDesk
// ID). Deterministic so it survives reconnects without any persistence.
fn numeric_id(identity_id: &str) -> String {
    let mut h: u64 = 1469598103934665603; // FNV-1a
    for byte in identity_id.bytes() {
        h ^= byte as u64;
        h = h.wrapping_mul(1099511628211);
    }
    let n = h % 1_000_000_000; // 0..=999,999,999
    format!("{n:09}")
}

// Open enrollment: when OPEN_ENROLLMENT=1, new identities self-enroll with just a
// key (no code) — the AnyDesk model, where the host's shown ID+PIN is the gate.
fn open_enrollment() -> bool {
    matches!(std::env::var("OPEN_ENROLLMENT").as_deref(), Ok("1") | Ok("true"))
}

fn handle_admin(state: &Arc<AppState>, tx: &UnboundedSender<Message>, mtype: &str, msg: &Value) {
    match mtype {
        "admin_identities" => {
            let list: Vec<Value> = state
                .keys
                .lock()
                .unwrap()
                .iter()
                .map(|(id, k)| json!({ "identityId": id, "kind": k.kind }))
                .collect();
            send_json(tx, json!({ "type": "admin_identities", "identities": list }));
        }
        "admin_devices" => {
            let list: Vec<String> = state.devices.lock().unwrap().keys().cloned().collect();
            send_json(tx, json!({ "type": "admin_devices", "devices": list }));
        }
        "admin_revoke" => {
            let id = msg.get("identityId").and_then(Value::as_str).unwrap_or("").to_string();
            if id.is_empty() {
                return send_json(tx, json!({ "type": "admin_error", "message": "identityId required" }));
            }
            // Remove from BOTH in-memory and DB (the desync that bit us before).
            let existed = state.keys.lock().unwrap().remove(&id).is_some();
            state.devices.lock().unwrap().remove(&id);
            if let Some(peer_tx) = state.peers.lock().unwrap().remove(&id) {
                let _ = peer_tx.send(Message::Close(None)); // kick the live connection
            }
            if let Some(pool) = &state.db {
                let pool = pool.clone();
                let id2 = id.clone();
                tokio::spawn(async move {
                    let _ = sqlx::query("DELETE FROM enrolled_keys WHERE identity_id = $1")
                        .bind(id2)
                        .execute(&pool)
                        .await;
                });
            }
            println!("Admin revoked identity: {id}");
            send_json(tx, json!({ "type": "admin_ok", "revoked": id, "existed": existed }));
        }
        "admin_mint_code" => {
            let kind = msg.get("kind").and_then(Value::as_str).unwrap_or("device");
            if kind != "device" && kind != "controller" {
                return send_json(tx, json!({ "type": "admin_error", "message": "kind must be device|controller" }));
            }
            let code = random_code();
            state.enroll_codes.lock().unwrap().insert(
                code.clone(),
                EnrollCode { kind: kind.to_string(), used: false },
            );
            println!("Admin minted {kind} enroll code");
            send_json(tx, json!({ "type": "admin_ok", "code": code, "kind": kind }));
        }
        "admin_sessions" => {
            let pool = match &state.db {
                Some(p) => p.clone(),
                None => {
                    return send_json(tx, json!({ "type": "admin_error", "message": "no database configured (set DATABASE_URL)" }))
                }
            };
            let tx = tx.clone();
            tokio::spawn(async move {
                let rows = sqlx::query_as::<_, (String, String, String, String, i64, Option<i64>)>(
                    "SELECT session_id, controller_id, device_id, status, start_ms, duration_ms \
                     FROM sessions ORDER BY start_ms DESC LIMIT 50",
                )
                .fetch_all(&pool)
                .await
                .unwrap_or_default();
                let list: Vec<Value> = rows
                    .into_iter()
                    .map(|(sid, cid, did, st, start, dur)| {
                        json!({ "sessionId": sid, "controllerId": cid, "deviceId": did,
                                "status": st, "startMs": start, "durationMs": dur })
                    })
                    .collect();
                let _ = tx.send(Message::Text(json!({ "type": "admin_sessions", "sessions": list }).to_string()));
            });
        }
        _ => send_json(tx, json!({ "type": "admin_error", "message": "unknown admin command" })),
    }
}

fn handle_text(state: &Arc<AppState>, conn: &mut ConnState, tx: &UnboundedSender<Message>, text: &str) {
    let msg: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(_) => return send_json(tx, json!({ "type": "error", "message": "Invalid JSON" })),
    };
    let mtype = msg.get("type").and_then(Value::as_str).unwrap_or("");

    // --- auth handshake (only messages allowed pre-auth) ---
    if mtype == "auth_init" {
        match start_handshake(state, conn, &msg) {
            Ok(nonce) => send_json(tx, json!({ "type": "auth_challenge", "nonce": nonce })),
            Err(e) => send_json(tx, json!({ "type": "auth_error", "message": e })),
        }
        return;
    }
    if mtype == "auth_response" {
        let signature = msg.get("signature").and_then(Value::as_str).unwrap_or("");
        match complete_handshake(state, conn, signature) {
            Ok(identity) => {
                state.peers.lock().unwrap().insert(identity.id.clone(), tx.clone());
                let (id, kind) = (identity.id.clone(), identity.kind.clone());
                conn.identity = Some(identity);
                send_json(tx, json!({ "type": "auth_ok", "identityId": id, "kind": kind }));
            }
            Err(e) => send_json(tx, json!({ "type": "auth_error", "message": e })),
        }
        return;
    }

    // --- Admin channel (gated by ADMIN_TOKEN, separate from identity auth) ---
    if mtype == "admin_auth" {
        let token = msg.get("token").and_then(Value::as_str).unwrap_or("");
        let expected = std::env::var("ADMIN_TOKEN").unwrap_or_default();
        if !expected.is_empty() && token == expected {
            conn.is_admin = true;
            return send_json(tx, json!({ "type": "admin_ok" }));
        }
        return send_json(tx, json!({ "type": "admin_error", "message": "invalid admin token" }));
    }
    if mtype.starts_with("admin_") {
        if !conn.is_admin {
            return send_json(tx, json!({ "type": "admin_error", "message": "not authorized" }));
        }
        handle_admin(state, tx, mtype, &msg);
        return;
    }

    // --- everything below requires an authenticated identity ---
    let identity = match &conn.identity {
        Some(i) => i.clone(),
        None => return send_json(tx, json!({ "type": "error", "message": "Not authenticated" })),
    };

    match mtype {
        "register" => {
            if identity.kind != "device" {
                return send_json(tx, json!({ "type": "error", "message": "Only device identities can register" }));
            }
            if let Some(dev) = msg.get("deviceId").and_then(Value::as_str) {
                if dev != identity.id {
                    return send_json(tx, json!({ "type": "error", "message": "deviceId must match authenticated identity" }));
                }
            }
            let metadata = msg.get("metadata").cloned().unwrap_or(json!({}));
            state.devices.lock().unwrap().insert(identity.id.clone(), metadata);
            let num = numeric_id(&identity.id);
            state.device_numeric.lock().unwrap().insert(num.clone(), identity.id.clone());
            conn.registered_device_id = Some(identity.id.clone());
            println!("Device registered: {} (connect ID {num})", identity.id);
            // connectId is the 9-digit number the host shows and controllers dial.
            send_json(tx, json!({ "type": "registered", "deviceId": identity.id, "connectId": num }));
        }

        "connect_request" => {
            if identity.kind != "controller" {
                return send_json(tx, json!({ "type": "error", "message": "Only controller identities can request connections" }));
            }
            // New (AnyDesk-style): dial by 9-digit partnerId. Legacy: targetDeviceId.
            let partner_id = msg.get("partnerId").and_then(Value::as_str).unwrap_or("");
            let pin = msg.get("pin").and_then(Value::as_str).unwrap_or("").to_string();
            let target = if !partner_id.is_empty() {
                match state.device_numeric.lock().unwrap().get(partner_id) {
                    Some(dev) => dev.clone(),
                    None => return send_json(tx, json!({ "type": "error", "message": "No online machine with that ID" })),
                }
            } else {
                msg.get("targetDeviceId").and_then(Value::as_str).unwrap_or("").to_string()
            };
            let online = state.devices.lock().unwrap().contains_key(&target);
            if !online {
                return send_json(tx, json!({ "type": "error", "message": "Device offline or not found" }));
            }
            let session_id = uuid::Uuid::new_v4().to_string();
            state.sessions.lock().unwrap().insert(
                session_id.clone(),
                Session {
                    session_id: session_id.clone(),
                    controller_id: identity.id.clone(),
                    device_id: target.clone(),
                    status: "pending".to_string(),
                    start_time: now_ms(),
                    end_time: None,
                },
            );
            // Forward the PIN so the host validates it (server just relays it).
            send_peer(state, &target, json!({
                "type": "incoming_request",
                "sessionId": session_id,
                "controllerId": identity.id,
                "pin": pin,
            }));
            send_json(tx, json!({ "type": "request_sent", "sessionId": session_id }));
        }

        "session_response" => {
            let session_id = msg.get("sessionId").and_then(Value::as_str).unwrap_or("").to_string();
            let accepted = msg.get("accepted").and_then(Value::as_bool).unwrap_or(false);
            let (controller_id, rejected_snap) = {
                let mut sessions = state.sessions.lock().unwrap();
                let session = match sessions.get_mut(&session_id) {
                    Some(s) => s,
                    None => return send_json(tx, json!({ "type": "error", "message": "Unknown session" })),
                };
                if session.device_id != identity.id {
                    return send_json(tx, json!({ "type": "error", "message": "Not authorized for this session" }));
                }
                let snap = if accepted {
                    session.status = "active".to_string();
                    None
                } else {
                    session.status = "rejected".to_string();
                    session.end_time = Some(now_ms());
                    Some(session.clone())
                };
                (session.controller_id.clone(), snap)
            };
            if let Some(s) = &rejected_snap {
                audit_session(state, s);
            }
            send_peer(state, &controller_id, json!({
                "type": "session_response",
                "sessionId": session_id,
                "accepted": accepted,
            }));
        }

        "offer" | "answer" | "ice_candidate" => {
            let session_id = msg.get("sessionId").and_then(Value::as_str).unwrap_or("").to_string();
            let counterpart = {
                let sessions = state.sessions.lock().unwrap();
                let session = match sessions.get(&session_id) {
                    Some(s) => s,
                    None => return send_json(tx, json!({ "type": "error", "message": "Unknown session" })),
                };
                if identity.id != session.controller_id && identity.id != session.device_id {
                    return send_json(tx, json!({ "type": "error", "message": "Not a participant of this session" }));
                }
                if session.status != "active" {
                    return send_json(tx, json!({ "type": "error", "message": "Session is not active" }));
                }
                if identity.id == session.controller_id {
                    session.device_id.clone()
                } else {
                    session.controller_id.clone()
                }
            };
            send_peer(state, &counterpart, msg.clone());
        }

        "end_session" => {
            let session_id = msg.get("sessionId").and_then(Value::as_str).unwrap_or("").to_string();
            let (counterpart, snap) = {
                let mut sessions = state.sessions.lock().unwrap();
                match sessions.get_mut(&session_id) {
                    Some(s) if s.controller_id == identity.id || s.device_id == identity.id => {
                        s.status = "ended".to_string();
                        s.end_time = Some(now_ms());
                        println!("Session ended: {}", s.session_id);
                        let cp = if s.controller_id == identity.id {
                            s.device_id.clone()
                        } else {
                            s.controller_id.clone()
                        };
                        (Some(cp), Some(s.clone()))
                    }
                    _ => (None, None),
                }
            };
            if let Some(s) = &snap {
                audit_session(state, s);
            }
            if let Some(counterpart) = counterpart {
                send_peer(state, &counterpart, json!({ "type": "session_ended", "sessionId": session_id }));
            }
        }

        _ => send_json(tx, json!({ "type": "error", "message": "Unknown message type" })),
    }
}

// ---------- auth handshake ----------

fn start_handshake(state: &Arc<AppState>, conn: &mut ConnState, msg: &Value) -> Result<String, String> {
    let identity_id = msg
        .get("identityId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or("identityId required")?
        .to_string();
    let req_kind = msg.get("kind").and_then(Value::as_str).map(str::to_string);
    let public_key = msg.get("publicKey").and_then(Value::as_str).map(str::to_string);
    let enroll_code = msg.get("enrollCode").and_then(Value::as_str).map(str::to_string);

    let existing = {
        let keys = state.keys.lock().unwrap();
        keys.get(&identity_id).map(|k| (k.public_key.clone(), k.kind.clone()))
    };

    let (challenge_key, resolved_kind, is_enrollment) = if let Some((pk, kind)) = existing {
        // Returning identity — challenge against enrolled key only.
        if let Some(provided) = &public_key {
            if provided != &pk {
                return Err("public key does not match enrolled identity".to_string());
            }
        }
        (pk, kind, false)
    } else if open_enrollment() {
        // AnyDesk model: self-enroll with just a key, no code. The host's shown
        // ID + PIN is the access gate, not a pre-issued code. Kind comes from the
        // client (defaults to controller).
        let pk = public_key.ok_or("publicKey required for enrollment")?;
        let kind = req_kind.clone().unwrap_or_else(|| "controller".to_string());
        if kind != "device" && kind != "controller" {
            return Err("kind must be device or controller".to_string());
        }
        (pk, kind, true)
    } else {
        // First contact (TOFU) — needs a public key and a valid enroll code.
        let pk = public_key.ok_or("publicKey required for enrollment")?;
        let code = enroll_code.clone().ok_or("enrollCode required for enrollment")?;
        let code_kind = {
            let codes = state.enroll_codes.lock().unwrap();
            match codes.get(&code) {
                Some(c) if !c.used => c.kind.clone(),
                _ => return Err("invalid or already-used enroll code".to_string()),
            }
        };
        if let Some(k) = &req_kind {
            if k != &code_kind {
                return Err(format!("enroll code is for a {code_kind}, not a {k}"));
            }
        }
        (pk, code_kind, true)
    };

    let nonce = random_nonce();
    conn.pending = Some(Pending {
        identity_id,
        kind: resolved_kind,
        public_key: challenge_key,
        nonce: nonce.clone(),
        is_enrollment,
        enroll_code: if is_enrollment { enroll_code } else { None },
        expires_at: Instant::now() + CHALLENGE_TTL,
    });
    Ok(nonce)
}

fn complete_handshake(state: &Arc<AppState>, conn: &mut ConnState, signature: &str) -> Result<Identity, String> {
    let p = conn.pending.take().ok_or("no handshake in progress")?;

    if Instant::now() > p.expires_at {
        return Err("challenge expired".to_string());
    }
    if signature.is_empty() {
        return Err("signature required".to_string());
    }
    if !verify_sig(&p.public_key, p.nonce.as_bytes(), signature) {
        return Err("signature verification failed".to_string());
    }

    if p.is_enrollment {
        state.keys.lock().unwrap().insert(
            p.identity_id.clone(),
            EnrolledKey { public_key: p.public_key.clone(), kind: p.kind.clone() },
        );
        if let Some(code) = &p.enroll_code {
            if let Some(c) = state.enroll_codes.lock().unwrap().get_mut(code) {
                c.used = true;
            }
        }
        // Durably persist the enrolled key so it survives restarts.
        // TODO: also persist enroll-code usage (codes reset to unused on restart).
        if let Some(pool) = &state.db {
            let pool = pool.clone();
            let (id, pk, kind) = (p.identity_id.clone(), p.public_key.clone(), p.kind.clone());
            tokio::spawn(async move {
                let _ = sqlx::query(
                    "INSERT INTO enrolled_keys (identity_id, public_key, kind) VALUES ($1,$2,$3) \
                     ON CONFLICT (identity_id) DO NOTHING",
                )
                .bind(id)
                .bind(pk)
                .bind(kind)
                .execute(&pool)
                .await;
            });
        }
        println!("Enrolled {} identity: {}", p.kind, p.identity_id);
    }

    Ok(Identity { id: p.identity_id, kind: p.kind })
}
