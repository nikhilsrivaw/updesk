// UpDesk silent native host — Phase 1: prove we can capture the screen with NO
// gesture, NO picker, NO window (unlike the webview's getDisplayMedia).
//
// Grabs one frame from the primary display via the Desktop Duplication API
// (through `scrap`) and writes it to `native-capture.png`, printing timing.
// If this runs and produces a real screenshot with zero user interaction, the
// silent-capture foundation works.

use scrap::{Capturer, Display};
use std::io::ErrorKind::WouldBlock;
use std::thread;
use std::time::{Duration, Instant};

fn main() {
    let display = match Display::primary() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("no primary display: {e}");
            std::process::exit(1);
        }
    };
    let (w, h) = (display.width(), display.height());
    println!("Primary display: {w}x{h}");

    let mut capturer = Capturer::new(display).expect("create capturer");
    let started = Instant::now();

    // Pull frames until we get a full one (the first few can be WouldBlock).
    let mut frames = 0u32;
    loop {
        match capturer.frame() {
            Ok(frame) => {
                frames += 1;
                // Grab a couple so timing is representative, then save the last.
                if frames >= 3 {
                    let stride = frame.len() / h;
                    println!(
                        "Captured {frames} frames silently in {:?} (last = {} bytes, stride {stride})",
                        started.elapsed(),
                        frame.len()
                    );
                    save_png(&frame, w, h, stride);
                    break;
                }
            }
            Err(ref e) if e.kind() == WouldBlock => {
                thread::sleep(Duration::from_millis(16)); // wait for the next frame
            }
            Err(e) => {
                eprintln!("capture error: {e}");
                std::process::exit(1);
            }
        }
    }
}

// scrap frames are BGRA with row padding (stride may exceed width*4). Convert to
// tightly-packed RGBA and write a PNG.
fn save_png(frame: &[u8], w: usize, h: usize, stride: usize) {
    let mut rgba = vec![0u8; w * h * 4];
    for y in 0..h {
        for x in 0..w {
            let src = y * stride + x * 4;
            let dst = (y * w + x) * 4;
            rgba[dst] = frame[src + 2]; // R (from B position — BGRA -> RGBA)
            rgba[dst + 1] = frame[src + 1]; // G
            rgba[dst + 2] = frame[src]; // B
            rgba[dst + 3] = 255; // A
        }
    }
    match image::save_buffer(
        "native-capture.png",
        &rgba,
        w as u32,
        h as u32,
        image::ColorType::Rgba8,
    ) {
        Ok(_) => println!("Saved native-capture.png — silent capture works ✓"),
        Err(e) => eprintln!("save failed: {e}"),
    }
}
