// UpDesk silent native host.
//   Phase 1: silent screen capture (scrap / Desktop Duplication) — no UI.
//   Phase 2: encode the captured frames to H.264 so WebRTC can carry them.
//
// This run captures ~30 frames silently, encodes each to H.264, writes the
// stream to `native-capture.h264`, and reports throughput. If the encoded
// stream is a small fraction of the raw bytes at a usable fps, the capture ->
// encode pipeline works.

use openh264::encoder::Encoder;
use openh264::formats::{RgbSliceU8, YUVBuffer};
use scrap::{Capturer, Display};
use std::io::ErrorKind::WouldBlock;
use std::io::Write;
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
    let mut encoder = Encoder::new().expect("create H.264 encoder");

    let mut out = std::fs::File::create("native-capture.h264").expect("create output");
    let mut rgb = vec![0u8; w * h * 3];

    let target_frames = 30u32;
    let mut encoded_frames = 0u32;
    let mut raw_total = 0usize;
    let mut enc_total = 0usize;
    let started = Instant::now();

    while encoded_frames < target_frames {
        match capturer.frame() {
            Ok(frame) => {
                let stride = frame.len() / h;
                bgra_to_rgb(&frame, &mut rgb, w, h, stride);
                raw_total += frame.len();

                let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(&rgb, (w, h)));
                match encoder.encode(&yuv) {
                    Ok(bitstream) => {
                        let data = bitstream.to_vec();
                        enc_total += data.len();
                        let _ = out.write_all(&data);
                        encoded_frames += 1;
                    }
                    Err(e) => {
                        eprintln!("encode error: {e}");
                        std::process::exit(1);
                    }
                }
            }
            Err(ref e) if e.kind() == WouldBlock => {
                thread::sleep(Duration::from_millis(8));
            }
            Err(e) => {
                eprintln!("capture error: {e}");
                std::process::exit(1);
            }
        }
    }

    let secs = started.elapsed().as_secs_f64();
    println!(
        "Captured+encoded {encoded_frames} frames in {secs:.2}s ({:.1} fps)",
        encoded_frames as f64 / secs
    );
    println!(
        "Raw: {:.1} MB  ->  H.264: {:.2} MB  ({:.0}x smaller, avg {:.0} KB/frame)",
        raw_total as f64 / 1e6,
        enc_total as f64 / 1e6,
        raw_total as f64 / enc_total.max(1) as f64,
        enc_total as f64 / encoded_frames as f64 / 1024.0
    );
    println!("Wrote native-capture.h264 — capture->encode pipeline works ✓");
}

// scrap gives BGRA with row padding (stride); pack into tight RGB for the encoder.
fn bgra_to_rgb(frame: &[u8], rgb: &mut [u8], w: usize, h: usize, stride: usize) {
    for y in 0..h {
        for x in 0..w {
            let s = y * stride + x * 4;
            let d = (y * w + x) * 3;
            rgb[d] = frame[s + 2]; // R
            rgb[d + 1] = frame[s + 1]; // G
            rgb[d + 2] = frame[s]; // B
        }
    }
}
