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
    let mut n = 0u64;
    let mut have = false;
    let mut last_send = std::time::Instant::now();

    loop {
        match capturer.frame() {
            Ok(frame) => {
                let stride = frame.len() / h;
                bgra_to_rgb(&frame, &mut rgb, w, h, stride);
                have = true;
                // Keyframe on the first frame + ~every 2s, so a controller joining
                // mid-stream always gets a decodable IDR.
                if n % 60 == 0 {
                    encoder.force_intra_frame();
                }
                if !encode_send(&mut encoder, &rgb, w, h, &tx) { break; }
                n += 1;
                last_send = std::time::Instant::now();
            }
            Err(ref e) if e.kind() == WouldBlock => {
                // Static screen: scrap emits nothing. Resend the last frame as a
                // keyframe every ~300ms so the decoder has something to show.
                if have && last_send.elapsed() > Duration::from_millis(300) {
                    encoder.force_intra_frame();
                    if !encode_send(&mut encoder, &rgb, w, h, &tx) { break; }
                    last_send = std::time::Instant::now();
                } else {
                    thread::sleep(Duration::from_millis(8));
                }
            }
            Err(e) => { eprintln!("[capture] error: {e}"); break; }
        }
    }
}

// Encode one RGB frame and send it; returns false if the receiver is gone.
fn encode_send(
    encoder: &mut Encoder,
    rgb: &[u8],
    w: usize,
    h: usize,
    tx: &Sender<Vec<u8>>,
) -> bool {
    let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(rgb, (w, h)));
    match encoder.encode(&yuv) {
        Ok(bs) => tx.blocking_send(bs.to_vec()).is_ok(),
        Err(_) => true, // skip a bad frame, keep going
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
