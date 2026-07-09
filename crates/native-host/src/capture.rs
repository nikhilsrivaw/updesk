// Silent screen capture + H.264 encode, streamed as encoded frames over a
// channel. Runs on its own thread (scrap + openh264 are blocking/sync).

use openh264::encoder::Encoder;
use openh264::formats::{RgbSliceU8, YUVBuffer};
use scrap::{Capturer, Display};
use std::io::ErrorKind::WouldBlock;
use std::thread;
use std::time::Duration;
use tokio::sync::mpsc::Sender;

/// Capture the primary display and send H.264 frames until the receiver drops.
pub fn run(tx: Sender<Vec<u8>>) {
    let display = match Display::primary() {
        Ok(d) => d,
        Err(e) => { eprintln!("[capture] no display: {e}"); return; }
    };
    let (w, h) = (display.width(), display.height());
    let mut capturer = match Capturer::new(display) {
        Ok(c) => c,
        Err(e) => { eprintln!("[capture] capturer: {e}"); return; }
    };
    let mut encoder = match Encoder::new() {
        Ok(e) => e,
        Err(e) => { eprintln!("[capture] encoder: {e}"); return; }
    };
    let mut rgb = vec![0u8; w * h * 3];
    loop {
        match capturer.frame() {
            Ok(frame) => {
                let stride = frame.len() / h;
                bgra_to_rgb(&frame, &mut rgb, w, h, stride);
                let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(&rgb, (w, h)));
                if let Ok(bs) = encoder.encode(&yuv) {
                    if tx.blocking_send(bs.to_vec()).is_err() {
                        break; // receiver gone -> session ended
                    }
                }
            }
            Err(ref e) if e.kind() == WouldBlock => thread::sleep(Duration::from_millis(8)),
            Err(e) => { eprintln!("[capture] error: {e}"); break; }
        }
    }
}

fn bgra_to_rgb(frame: &[u8], rgb: &mut [u8], w: usize, h: usize, stride: usize) {
    for y in 0..h {
        for x in 0..w {
            let s = y * stride + x * 4;
            let d = (y * w + x) * 3;
            rgb[d] = frame[s + 2];
            rgb[d + 1] = frame[s + 1];
            rgb[d + 2] = frame[s];
        }
    }
}
