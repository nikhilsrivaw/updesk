// UpDesk controller — the support-staff viewer.
//
// The webview renders the incoming WebRTC video and captures input (sent over
// the data channel). Native commands: the clipboard bridge and saving received
// file transfers — both used to sync with the host machine.

use arboard::Clipboard;

// Best-effort: a clipboard momentarily locked by another app yields "".
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            get_clipboard,
            set_clipboard,
            save_download
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
