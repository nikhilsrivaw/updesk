// UpDesk silent native host — Phase 4: connectable.
//
// Full native stack (no webview): silent capture -> H.264 -> WebRTC, wired to
// the SAME signaling server + Ed25519 protocol as the other hosts. A controller
// connects with this host's 9-digit ID + the unattended password, and it
// auto-accepts silently and streams the screen.
//
//   UPDESK_PW  — unattended password (default "updesk")
//   UPDESK_URL — signaling server (default wss://updesk.duckdns.org)

mod capture;
mod input;
#[cfg(windows)]
mod service;

use anyhow::Result;
use base64::Engine;
use ed25519_dalek::{Signer, SigningKey, VerifyingKey};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{connect_async, MaybeTlsStream, WebSocketStream};
use webrtc::api::interceptor_registry::register_default_interceptors;
use webrtc::api::media_engine::{MediaEngine, MIME_TYPE_H264};
use webrtc::api::{APIBuilder, API};
use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
use webrtc::ice_transport::ice_credential_type::RTCIceCredentialType;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::interceptor::registry::Registry;
use webrtc::media::Sample;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::rtp_transceiver::rtp_codec::RTCRtpCodecCapability;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;

type WsWrite = futures_util::stream::SplitSink<WebSocketStream<MaybeTlsStream<TcpStream>>, Message>;
type SharedWrite = Arc<Mutex<WsWrite>>;

fn main() -> Result<()> {
    // Subcommands for unattended service deployment; default = stream now.
    match std::env::args().nth(1).as_deref() {
        #[cfg(windows)]
        Some("install") => return service::install(),
        #[cfg(windows)]
        Some("uninstall") => return service::uninstall(),
        #[cfg(windows)]
        Some("service") => return service::run_dispatcher(),
        _ => {}
    }
    tokio::runtime::Runtime::new()?.block_on(stream_main())
}

async fn stream_main() -> Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let url = std::env::var("UPDESK_URL").unwrap_or_else(|_| "wss://updesk.duckdns.org".into());
    let password = std::env::var("UPDESK_PW").unwrap_or_else(|_| "updesk".into());
    let identity = Identity::load();
    println!("Native host identity: {}", identity.id);

    let api = Arc::new(build_api()?);
    let input_tx = input::spawn(); // dedicated input-injection thread

    let (ws, _) = connect_async(&url).await?;
    println!("Connected to {url}");
    let (write, mut read) = ws.split();
    let write: SharedWrite = Arc::new(Mutex::new(write));

    // Kick off the Ed25519 handshake.
    send(&write, json!({
        "type": "auth_init",
        "identityId": identity.id,
        "kind": "device",
        "publicKey": identity.spki_base64(),
    })).await?;

    // Only one session at a time in this build.
    let mut pc: Option<Arc<RTCPeerConnection>> = None;

    while let Some(msg) = read.next().await {
        let text = match msg? { Message::Text(t) => t, Message::Close(_) => break, _ => continue };
        let v: Value = match serde_json::from_str(&text) { Ok(v) => v, Err(_) => continue };
        match v["type"].as_str().unwrap_or("") {
            "auth_challenge" => {
                let nonce = v["nonce"].as_str().unwrap_or("");
                let sig = identity.sign_b64(nonce.as_bytes());
                send(&write, json!({ "type": "auth_response", "signature": sig })).await?;
            }
            "auth_ok" => {
                send(&write, json!({
                    "type": "register", "deviceId": identity.id,
                    "metadata": { "os": "windows", "app": "updesk-native-host" }
                })).await?;
            }
            "auth_error" => { eprintln!("auth error: {}", v["message"]); break; }
            "registered" => {
                let id = v["connectId"].as_str().unwrap_or("");
                let fmt = if id.len() == 9 { format!("{} {} {}", &id[0..3], &id[3..6], &id[6..9]) } else { id.into() };
                println!("=======================================");
                println!(" ONLINE — silent unattended host");
                println!("   Your ID:  {fmt}");
                println!("   Password: {password}");
                println!("   Controllers connect with these — no prompt here.");
                println!("=======================================");
            }
            "incoming_request" => {
                let sid = v["sessionId"].as_str().unwrap_or("").to_string();
                let supplied = v["pin"].as_str().unwrap_or("");
                if supplied != password {
                    send(&write, json!({ "type": "session_response", "sessionId": sid, "accepted": false })).await?;
                    println!("rejected a connection (wrong password)");
                    continue;
                }
                println!("unattended connect accepted — streaming screen");
                send(&write, json!({ "type": "session_response", "sessionId": sid, "accepted": true })).await?;
                match start_session(&api, &write, sid.clone(), input_tx.clone()).await {
                    Ok(new_pc) => pc = Some(new_pc),
                    Err(e) => eprintln!("session setup failed (host stays online): {e}"),
                }
            }
            "answer" => {
                if let Some(pc) = &pc {
                    let sdp = v["sdp"].as_str().unwrap_or("");
                    let answer = RTCSessionDescription::answer(sdp.to_string())?;
                    let _ = pc.set_remote_description(answer).await;
                    println!("answer applied");
                }
            }
            "ice_candidate" => {
                if let Some(pc) = &pc {
                    let c = &v["candidate"];
                    let init = RTCIceCandidateInit {
                        candidate: c["candidate"].as_str().unwrap_or("").to_string(),
                        sdp_mid: c["sdpMid"].as_str().map(String::from),
                        sdp_mline_index: c["sdpMLineIndex"].as_u64().map(|x| x as u16),
                        username_fragment: None,
                    };
                    let _ = pc.add_ice_candidate(init).await;
                }
            }
            "session_ended" | "peer_disconnected" => {
                if let Some(pc) = pc.take() { let _ = pc.close().await; }
                println!("session ended");
            }
            _ => {}
        }
    }
    Ok(())
}

// Build one WebRTC session: peer connection + screen track + offer.
async fn start_session(api: &Arc<API>, write: &SharedWrite, sid: String, input_tx: std::sync::mpsc::Sender<Value>) -> Result<Arc<RTCPeerConnection>> {
    let config = RTCConfiguration {
        ice_servers: vec![
            RTCIceServer { urls: vec!["stun:stun.l.google.com:19302".into()], ..Default::default() },
            RTCIceServer {
                urls: vec!["turn:updesk.duckdns.org:3478".into()],
                username: "updesk".into(),
                credential: "updesk_turn_9fKq2mXz7L".into(),
                credential_type: RTCIceCredentialType::Password,
            },
        ],
        ..Default::default()
    };
    let pc = Arc::new(api.new_peer_connection(config).await?);

    let track = Arc::new(TrackLocalStaticSample::new(
        RTCRtpCodecCapability { mime_type: MIME_TYPE_H264.to_owned(), ..Default::default() },
        "video".to_owned(),
        "updesk-screen".to_owned(),
    ));
    pc.add_track(track.clone()).await?;

    // Input channel: controller sends mouse/keyboard events here; inject natively.
    let input_dc = pc.create_data_channel("input", None).await?;
    input_dc.on_message(Box::new(move |msg| {
        let itx = input_tx.clone();
        Box::pin(async move {
            if let Ok(v) = serde_json::from_slice::<Value>(&msg.data) {
                let _ = itx.send(v);
            }
        })
    }));

    // Diagnostics: watch the connection come up (or not).
    pc.on_peer_connection_state_change(Box::new(|s| {
        println!("[pc] {s}");
        Box::pin(async {})
    }));
    pc.on_ice_connection_state_change(Box::new(|s| {
        println!("[ice] {s}");
        Box::pin(async {})
    }));

    // capture+encode thread -> channel -> track
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<u8>>(8);
    std::thread::spawn(move || capture::run(tx));
    tokio::spawn(async move {
        let mut n = 0u64;
        while let Some(data) = rx.recv().await {
            let bytes = data.len();
            match track.write_sample(&Sample {
                data: data.into(),
                duration: std::time::Duration::from_millis(33),
                ..Default::default()
            }).await {
                Ok(_) => { n += 1; if n <= 3 || n % 30 == 0 { println!("[video] wrote sample {n} ({bytes} B)"); } }
                Err(e) => { println!("[video] write_sample error: {e}"); break; }
            }
        }
        println!("[video] writer stopped after {n} samples");
    });

    // send our ICE candidates to the controller
    let w = write.clone();
    let sid2 = sid.clone();
    pc.on_ice_candidate(Box::new(move |cand| {
        let w = w.clone();
        let sid = sid2.clone();
        Box::pin(async move {
            if let Some(c) = cand {
                if let Ok(init) = c.to_json() {
                    let msg = json!({
                        "type": "ice_candidate", "sessionId": sid,
                        "candidate": { "candidate": init.candidate, "sdpMid": init.sdp_mid, "sdpMLineIndex": init.sdp_mline_index }
                    });
                    let _ = send(&w, msg).await;
                }
            }
        })
    }));

    // offer
    let offer = pc.create_offer(None).await?;
    pc.set_local_description(offer).await?;
    if let Some(local) = pc.local_description().await {
        send(write, json!({ "type": "offer", "sessionId": sid, "sdp": local.sdp })).await?;
        println!("offer sent");
    }
    Ok(pc)
}

fn build_api() -> Result<API> {
    let mut m = MediaEngine::default();
    m.register_default_codecs()?;
    let mut registry = Registry::new();
    registry = register_default_interceptors(registry, &mut m)?;
    Ok(APIBuilder::new().with_media_engine(m).with_interceptor_registry(registry).build())
}

async fn send(write: &SharedWrite, msg: Value) -> Result<()> {
    let mut w = write.lock().await;
    w.send(Message::Text(msg.to_string())).await?;
    Ok(())
}

// --- stable Ed25519 identity, persisted next to the binary ---
struct Identity { id: String, key: SigningKey }
impl Identity {
    fn load() -> Self {
        let path = "native-host-identity.txt";
        if let Ok(s) = std::fs::read_to_string(path) {
            let mut lines = s.lines();
            if let (Some(id), Some(seed_b64)) = (lines.next(), lines.next()) {
                if let Ok(seed) = base64::engine::general_purpose::STANDARD.decode(seed_b64.trim()) {
                    if seed.len() == 32 {
                        let arr: [u8; 32] = seed.try_into().unwrap();
                        return Identity { id: id.trim().to_string(), key: SigningKey::from_bytes(&arr) };
                    }
                }
            }
        }
        // generate + persist
        let key = SigningKey::generate(&mut rand::rngs::OsRng);
        let id = format!("nativehost-{:08x}", rand::random::<u32>());
        let seed_b64 = base64::engine::general_purpose::STANDARD.encode(key.to_bytes());
        let _ = std::fs::write(path, format!("{id}\n{seed_b64}\n"));
        Identity { id, key }
    }

    // Base64 SPKI DER of the public key (server reads the trailing 32 bytes).
    fn spki_base64(&self) -> String {
        let vk: VerifyingKey = self.key.verifying_key();
        let mut der = vec![0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00];
        der.extend_from_slice(vk.as_bytes());
        base64::engine::general_purpose::STANDARD.encode(&der)
    }

    fn sign_b64(&self, msg: &[u8]) -> String {
        base64::engine::general_purpose::STANDARD.encode(self.key.sign(msg).to_bytes())
    }
}
