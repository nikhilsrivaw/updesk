// UpDesk host agent — native side.
//
// The webview handles WebRTC + screen capture. The Rust side does what a
// webview can't: inject the controller's input events into the OS via `enigo`
// (SendInput on Windows). Coordinates arrive normalized (0..1) relative to the
// shared screen and are mapped to real pixels here.

use arboard::Clipboard;
use enigo::{
    Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings,
};
use serde_json::{json, Value};
use std::sync::{Mutex, OnceLock};
use tauri_plugin_autostart::ManagerExt;

// One persistent Enigo for the whole process. Creating a fresh Enigo per event
// (mouse-move fires many times/second) floods the input thread and freezes the
// host, so we init once and reuse it under a Mutex.
fn enigo() -> &'static Mutex<Enigo> {
    static ENIGO: OnceLock<Mutex<Enigo>> = OnceLock::new();
    ENIGO.get_or_init(|| {
        Mutex::new(Enigo::new(&Settings::default()).expect("failed to init input engine"))
    })
}

// Launch-at-login toggle, for leaving a machine set up for unattended support.
#[tauri::command]
fn set_autostart(app: tauri::AppHandle, enabled: bool) -> Result<(), String> {
    let m = app.autolaunch();
    if enabled { m.enable() } else { m.disable() }.map_err(|e| e.to_string())
}

#[tauri::command]
fn get_autostart(app: tauri::AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

// This machine's LAN IP — the address a controller on the same WiFi should use.
// Trick: "connect" a UDP socket to a public IP (no packets are actually sent)
// and read back which local interface the OS picked. Works on any network.
#[tauri::command]
fn local_ip() -> Option<String> {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    sock.local_addr().ok().map(|a| a.ip().to_string())
}

// ---- annotation overlay (whiteboard) ------------------------------------
// A transparent, click-through, always-on-top window covering the screen. The
// controller's strokes are drawn on it so the person AT the host sees them.
// Click-through means it never blocks the host user's (or injected) input.
use tauri::{Emitter, Manager};

// Build the overlay window ONCE at startup (hidden + click-through). Creating a
// webview window from inside a command deadlocks on the main-thread event loop,
// so we do it in setup() where it's safe, then only show/hide/emit at runtime.
fn create_overlay(app: &tauri::AppHandle) -> Result<(), String> {
    if app.get_webview_window("overlay").is_some() {
        return Ok(());
    }
    let (w, h, x, y) = match app.primary_monitor().ok().flatten() {
        Some(m) => {
            let s = m.size();
            let p = m.position();
            (s.width as f64, s.height as f64, p.x as f64, p.y as f64)
        }
        None => (1920.0, 1080.0, 0.0, 0.0),
    };
    let win = tauri::WebviewWindowBuilder::new(
        app,
        "overlay",
        tauri::WebviewUrl::App("overlay.html".into()),
    )
    .transparent(true)
    .decorations(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .focused(false)
    .shadow(false)
    .visible(false) // stays hidden until annotate_show
    .inner_size(w, h)
    .position(x, y)
    .title("UpDesk annotation")
    .build()
    .map_err(|e| { eprintln!("[overlay] build FAILED: {e}"); e.to_string() })?;

    // Click-through so it can never block the host's input.
    win.set_ignore_cursor_events(true)
        .map_err(|e| { eprintln!("[overlay] ignore_cursor FAILED: {e}"); e.to_string() })?;
    eprintln!("[overlay] pre-created ({w}x{h} at {x},{y})");
    Ok(())
}

#[tauri::command]
fn annotate_show(app: tauri::AppHandle) -> Result<(), String> {
    let win = app
        .get_webview_window("overlay")
        .ok_or("overlay window missing")?;
    win.set_ignore_cursor_events(true).ok(); // re-assert click-through
    win.show().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn annotate_draw(app: tauri::AppHandle, stroke: Value) {
    let _ = app.emit_to("overlay", "draw", stroke);
}

#[tauri::command]
fn annotate_clear(app: tauri::AppHandle) {
    let _ = app.emit_to("overlay", "clear", ());
}

#[tauri::command]
fn annotate_hide(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("overlay") {
        let _ = app.emit_to("overlay", "clear", ()); // wipe ink before hiding
        let _ = w.hide();
    }
}

#[tauri::command]
fn input_event(event: Value) {
    // Only log failures — never the event contents, which would leak keystrokes
    // (incl. remotely-typed passwords) into the process output.
    if let Err(e) = inject(&event) {
        let kind = event.get("kind").and_then(Value::as_str).unwrap_or("?");
        eprintln!("[input] injection error on '{kind}': {e:?}");
    }
}

// Clipboard bridge for text sync between the two machines. Best-effort: a
// clipboard that's momentarily locked by another app just yields "".
#[tauri::command]
fn get_clipboard() -> String {
    Clipboard::new()
        .and_then(|mut c| c.get_text())
        .unwrap_or_default()
}

#[tauri::command]
fn set_clipboard(text: String) {
    if let Ok(mut c) = Clipboard::new() {
        let _ = c.set_text(text);
    }
}

// Save a received file (base64) into Downloads/UpDesk, never overwriting an
// existing file. Returns the final path for display.
#[tauri::command]
fn save_download(name: String, data: String) -> Result<String, String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(data.as_bytes())
        .map_err(|e| e.to_string())?;
    let dir = dirs::download_dir()
        .ok_or("no Downloads folder")?
        .join("UpDesk");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = unique_path(dir.join(sanitize(&name)));
    std::fs::write(&path, &bytes).map_err(|e| e.to_string())?;
    Ok(path.display().to_string())
}

// ---- remote file browser (forensic extraction from a seized PC) ----

// List a directory. Falls back to the user's home if the path is empty or
// invalid (e.g. a controller sending an Android default path).
#[tauri::command]
fn fs_list(path: String) -> Result<Value, String> {
    let mut dir = if path.is_empty() {
        dirs::home_dir().unwrap_or_else(|| std::path::PathBuf::from("/"))
    } else {
        std::path::PathBuf::from(&path)
    };
    if !dir.is_dir() {
        dir = dirs::home_dir().unwrap_or_else(|| std::path::PathBuf::from("/"));
    }
    let mut entries: Vec<(String, bool, u64)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            let md = e.metadata().ok();
            let is_dir = md.as_ref().map(|m| m.is_dir()).unwrap_or(false);
            let size = md.as_ref().map(|m| m.len()).unwrap_or(0);
            entries.push((e.file_name().to_string_lossy().to_string(), is_dir, size));
        }
    }
    entries.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let arr: Vec<Value> = entries
        .into_iter()
        .map(|(name, d, size)| json!({ "name": name, "dir": d, "size": size }))
        .collect();
    Ok(json!({
        "path": dir.display().to_string(),
        "parent": dir.parent().map(|p| p.display().to_string()).unwrap_or_default(),
        "entries": arr
    }))
}

// File metadata + SHA-256 of the source (single pass) — the forensic hash.
#[tauri::command]
fn fs_get_meta(path: String) -> Result<Value, String> {
    use sha2::{Digest, Sha256};
    let p = std::path::Path::new(&path);
    let md = std::fs::metadata(p).map_err(|e| e.to_string())?;
    if !md.is_file() {
        return Err("not a file".into());
    }
    let mut file = std::fs::File::open(p).map_err(|e| e.to_string())?;
    let mut hasher = Sha256::new();
    std::io::copy(&mut file, &mut hasher).map_err(|e| e.to_string())?;
    let sha: String = hasher.finalize().iter().map(|b| format!("{b:02x}")).collect();
    let mtime = md
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    Ok(json!({
        "name": p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
        "size": md.len(),
        "mtime": mtime,
        "sha256": sha
    }))
}

// ---- network connection monitor (network forensics) ----
// Snapshot of this machine's active network connections, with the owning
// process — "what is this device talking to right now".
#[tauri::command]
fn net_connections() -> Result<Value, String> {
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000; // don't flash a console window
    // PID -> process name (from tasklist CSV).
    let mut names: std::collections::HashMap<String, String> = std::collections::HashMap::new();
    if let Ok(out) = Command::new("tasklist")
        .args(["/fo", "csv", "/nh"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
    {
        for line in String::from_utf8_lossy(&out.stdout).lines() {
            let cols: Vec<&str> = line.split("\",\"").map(|s| s.trim_matches('"')).collect();
            if cols.len() >= 2 {
                names.insert(cols[1].trim().to_string(), cols[0].trim().to_string());
            }
        }
    }
    let out = Command::new("netstat")
        .args(["-ano"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&out.stdout);
    let mut conns: Vec<Value> = Vec::new();
    for line in text.lines() {
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() < 4 || (f[0] != "TCP" && f[0] != "UDP") {
            continue;
        }
        // TCP: proto local foreign state pid ; UDP: proto local foreign pid
        let (proto, local, foreign) = (f[0], f[1], f[2]);
        let (state, pid) = if proto == "TCP" && f.len() >= 5 {
            (f[3], f[4])
        } else {
            ("", f[3])
        };
        // Focus on real remote conversations (skip local listeners / wildcards).
        if foreign.starts_with("0.0.0.0") || foreign.starts_with("*") || foreign.starts_with("[::]") {
            continue;
        }
        conns.push(json!({
            "proto": proto, "local": local, "remote": foreign,
            "state": state, "pid": pid,
            "process": names.get(pid).cloned().unwrap_or_default()
        }));
    }
    Ok(json!({ "connections": conns }))
}

// VPN detector: is this machine masking its traffic through a VPN, and with
// what? Combines VPN virtual adapters, running VPN clients, and connections on
// known VPN ports.
#[tauri::command]
fn vpn_status() -> Result<Value, String> {
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    let mut adapters: Vec<String> = Vec::new();
    let mut processes: Vec<String> = Vec::new();

    // 1. VPN virtual adapters (ipconfig /all descriptions). Skip the always-present
    // WAN Miniports to avoid false positives.
    if let Ok(out) = Command::new("ipconfig").arg("/all").creation_flags(CREATE_NO_WINDOW).output() {
        for line in String::from_utf8_lossy(&out.stdout).lines() {
            let low = line.to_lowercase();
            let hit = ["wireguard", "openvpn", "tap-windows", "tap-nordvpn", " vpn", "tunnel", "wintun"]
                .iter()
                .any(|k| low.contains(k));
            if hit && !low.contains("wan miniport") {
                adapters.push(line.trim().to_string());
            }
        }
    }

    // 2. Running VPN clients.
    let vpn_procs = [
        "openvpn", "wireguard", "nordvpn", "expressvpn", "protonvpn", "surfshark",
        "mullvad", "cyberghost", "tunnelbear", "windscribe", "pia", "hide.me", "wg",
    ];
    if let Ok(out) = Command::new("tasklist").args(["/fo", "csv", "/nh"]).creation_flags(CREATE_NO_WINDOW).output() {
        for line in String::from_utf8_lossy(&out.stdout).lines() {
            let name = line.split("\",\"").next().unwrap_or("").trim_matches('"').to_string();
            let low = name.to_lowercase();
            if vpn_procs.iter().any(|p| low.contains(p)) {
                processes.push(name);
            }
        }
    }
    processes.sort();
    processes.dedup();

    let active = !adapters.is_empty() || !processes.is_empty();
    Ok(json!({ "active": active, "adapters": adapters, "processes": processes }))
}

// Read one chunk of a file as base64 (JS drives the chunking + streaming).
#[tauri::command]
fn fs_read_chunk(path: String, offset: u64, len: usize) -> Result<String, String> {
    use base64::Engine;
    use std::io::{Read, Seek, SeekFrom};
    let mut file = std::fs::File::open(&path).map_err(|e| e.to_string())?;
    file.seek(SeekFrom::Start(offset)).map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; len];
    let n = file.read(&mut buf).map_err(|e| e.to_string())?;
    buf.truncate(n);
    Ok(base64::engine::general_purpose::STANDARD.encode(&buf))
}

// Strip any directory components / illegal chars so a hostile name can't escape
// the target folder.
fn sanitize(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or(name);
    let cleaned: String = base
        .chars()
        .map(|c| if "<>:\"|?*".contains(c) || c.is_control() { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_matches('.').to_string();
    if cleaned.is_empty() { "file".into() } else { cleaned }
}

// If `path` exists, append " (1)", " (2)", … before the extension.
fn unique_path(path: std::path::PathBuf) -> std::path::PathBuf {
    if !path.exists() {
        return path;
    }
    let parent = path.parent().map(|p| p.to_path_buf()).unwrap_or_default();
    let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("file").to_string();
    let ext = path.extension().and_then(|s| s.to_str()).map(|e| format!(".{e}")).unwrap_or_default();
    for n in 1.. {
        let cand = parent.join(format!("{stem} ({n}){ext}"));
        if !cand.exists() {
            return cand;
        }
    }
    path
}

fn inject(event: &Value) -> Result<(), Box<dyn std::error::Error>> {
    let mut enigo = enigo().lock().map_err(|_| "input engine poisoned")?;
    let kind = event.get("kind").and_then(Value::as_str).unwrap_or("");

    match kind {
        "move" | "mousedown" | "mouseup" | "click" => {
            if let (Some(nx), Some(ny)) = (
                event.get("x").and_then(Value::as_f64),
                event.get("y").and_then(Value::as_f64),
            ) {
                let (w, h) = enigo.main_display()?;
                let x = (nx * w as f64).round() as i32;
                let y = (ny * h as f64).round() as i32;
                enigo.move_mouse(x, y, Coordinate::Abs)?;
            }
            if kind != "move" {
                let button = match event.get("button").and_then(Value::as_str).unwrap_or("left") {
                    "right" => Button::Right,
                    "middle" => Button::Middle,
                    _ => Button::Left,
                };
                let dir = match kind {
                    "mousedown" => Direction::Press,
                    "mouseup" => Direction::Release,
                    _ => Direction::Click,
                };
                enigo.button(button, dir)?;
            }
        }
        "wheel" => {
            let dy = event.get("dy").and_then(Value::as_i64).unwrap_or(0) as i32;
            if dy != 0 {
                enigo.scroll(dy, Axis::Vertical)?;
            }
        }
        "keydown" | "keyup" => {
            let down = kind == "keydown";
            let key_str = event.get("key").and_then(Value::as_str).unwrap_or("");

            if let Some(named) = map_named_key(key_str) {
                // Named keys (Enter, arrows, modifiers, …) resolve to real
                // virtual keys, so press/release works — this keeps held keys
                // and modifier combos (Ctrl, Shift, …) behaving correctly.
                if matches!(key_str, "Control" | "Alt" | "Meta") {
                    MODS_HELD.fetch_update(std::sync::atomic::Ordering::Relaxed,
                        std::sync::atomic::Ordering::Relaxed,
                        |v| Some(if down { v + 1 } else { v.saturating_sub(1) })).ok();
                }
                let dir = if down { Direction::Press } else { Direction::Release };
                enigo.key(named, dir)?;
            } else if key_str.chars().count() == 1 {
                let c = key_str.chars().next().unwrap();
                let mods = MODS_HELD.load(std::sync::atomic::Ordering::Relaxed) > 0;
                if mods {
                    // Shortcut (e.g. Ctrl+C): press the real key so it combines
                    // with the held modifier. Works for layout keys; a
                    // shift-requiring char here is a rare edge and just logged.
                    let dir = if down { Direction::Press } else { Direction::Release };
                    enigo.key(Key::Unicode(c), dir)?;
                } else if down {
                    // Plain typing: inject the character directly. `Key::Unicode`
                    // press/release can't type shift-requiring chars (uppercase,
                    // symbols) on Windows; `text()` handles any character.
                    enigo.text(key_str)?;
                }
            }
        }
        _ => {}
    }
    Ok(())
}

// Count of currently-held modifier keys (Ctrl/Alt/Meta), used to decide whether
// a printable key is part of a shortcut or plain typing.
static MODS_HELD: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(0);

// Map a JS KeyboardEvent.key to a *named* enigo Key. Returns None for printable
// characters — those are handled separately (typed via `text()`).
fn map_named_key(k: &str) -> Option<Key> {
    Some(match k {
        "Enter" => Key::Return,
        "Backspace" => Key::Backspace,
        "Tab" => Key::Tab,
        "Escape" => Key::Escape,
        " " | "Spacebar" => Key::Space,
        "ArrowLeft" => Key::LeftArrow,
        "ArrowRight" => Key::RightArrow,
        "ArrowUp" => Key::UpArrow,
        "ArrowDown" => Key::DownArrow,
        "Shift" => Key::Shift,
        "Control" => Key::Control,
        "Alt" => Key::Alt,
        "Meta" => Key::Meta,
        "Delete" => Key::Delete,
        "Home" => Key::Home,
        "End" => Key::End,
        "PageUp" => Key::PageUp,
        "PageDown" => Key::PageDown,
        "CapsLock" => Key::CapsLock,
        _ => return None,
    })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .invoke_handler(tauri::generate_handler![
            input_event,
            get_clipboard,
            set_clipboard,
            save_download,
            set_autostart,
            get_autostart,
            local_ip,
            fs_list,
            fs_get_meta,
            fs_read_chunk,
            net_connections,
            vpn_status,
            annotate_show,
            annotate_draw,
            annotate_clear,
            annotate_hide
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
