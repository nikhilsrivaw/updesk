// Native input injection for the silent host (control side). Runs on a dedicated
// thread that owns a single Enigo (SendInput on Windows) and consumes controller
// input events over a channel — same event shape as the desktop/Android hosts:
//   {kind:"move"|"mousedown"|"mouseup", x, y, button}  (x,y normalized 0..1)
//   {kind:"wheel", dy}
//   {kind:"keydown"|"keyup", key}

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};
use serde_json::Value;
use std::sync::mpsc::{channel, Receiver, Sender};

/// Start the input thread; returns a sender for controller events.
pub fn spawn() -> Sender<Value> {
    let (tx, rx) = channel::<Value>();
    std::thread::spawn(move || run(rx));
    tx
}

fn run(rx: Receiver<Value>) {
    let mut enigo = match Enigo::new(&Settings::default()) {
        Ok(e) => e,
        Err(e) => { eprintln!("[input] enigo init failed: {e:?}"); return; }
    };
    let mut mods_held = 0i32; // Ctrl/Alt/Meta currently down -> shortcut vs typing
    while let Ok(ev) = rx.recv() {
        if let Err(e) = inject(&mut enigo, &ev, &mut mods_held) {
            eprintln!("[input] inject error: {e:?}");
        }
    }
}

fn inject(enigo: &mut Enigo, e: &Value, mods_held: &mut i32) -> Result<(), Box<dyn std::error::Error>> {
    match e["kind"].as_str().unwrap_or("") {
        kind @ ("move" | "mousedown" | "mouseup" | "click") => {
            if let (Some(nx), Some(ny)) = (e["x"].as_f64(), e["y"].as_f64()) {
                let (w, h) = enigo.main_display()?;
                let x = (nx * w as f64).round() as i32;
                let y = (ny * h as f64).round() as i32;
                enigo.move_mouse(x, y, Coordinate::Abs)?;
            }
            if kind != "move" {
                let button = match e["button"].as_str().unwrap_or("left") {
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
            let dy = e["dy"].as_i64().unwrap_or(0) as i32;
            if dy != 0 { enigo.scroll(dy, Axis::Vertical)?; }
        }
        kind @ ("keydown" | "keyup") => {
            let down = kind == "keydown";
            let key = e["key"].as_str().unwrap_or("");
            if let Some(named) = map_named_key(key) {
                if matches!(key, "Control" | "Alt" | "Meta") {
                    *mods_held += if down { 1 } else { -1 };
                    if *mods_held < 0 { *mods_held = 0; }
                }
                enigo.key(named, if down { Direction::Press } else { Direction::Release })?;
            } else if key.chars().count() == 1 {
                let c = key.chars().next().unwrap();
                if *mods_held > 0 {
                    // shortcut (e.g. Ctrl+C): press the real key so it combines
                    enigo.key(Key::Unicode(c), if down { Direction::Press } else { Direction::Release })?;
                } else if down {
                    // plain typing: text() handles uppercase/symbols that Unicode
                    // press/release can't on Windows
                    enigo.text(key)?;
                }
            }
        }
        _ => {}
    }
    Ok(())
}

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
