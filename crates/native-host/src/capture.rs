// Silent screen capture + H.264 encode, streamed as encoded frames over a
// channel. Runs on its own thread (scrap + openh264 are blocking/sync).

use openh264::encoder::{Encoder, EncoderConfig, RateControlMode, UsageType};
use openh264::formats::{RgbSliceU8, YUVBuffer};
use openh264::OpenH264API;
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
    println!("[capture] display {w}x{h} (physical pixels)");
    let mut capturer = match Capturer::new(display) {
        Ok(c) => c,
        Err(e) => { eprintln!("[capture] capturer: {e}"); return; }
    };
    // Bitrate scales with resolution (~4 bits/px/s), clamped.
    let bitrate = ((w * h) as u32).saturating_mul(4).clamp(2_000_000, 12_000_000);

    // === Phase 3 (GPU hardware H.264 via Media Foundation) — IMPLEMENTED BUT
    // DISABLED. The software path below is the stable, demo-ready encoder. To
    // turn hardware back on later, just UNCOMMENT this block (it auto-falls back
    // to software if the GPU encoder isn't available, and UPDESK_ENCODER=software
    // forces software). The mf_encoder module + run_hardware()/bgra_to_nv12()
    // stay compiled and ready. ===
    //
    // let force_sw = std::env::var("UPDESK_ENCODER")
    //     .map(|v| v.eq_ignore_ascii_case("software")).unwrap_or(false);
    // #[cfg(windows)]
    // if !force_sw {
    //     match crate::mf_encoder::MfEncoder::spawn(w as u32, h as u32, 30, bitrate, tx.clone()) {
    //         Ok(enc) => {
    //             println!("[capture] encoder: HARDWARE H.264 (Media Foundation), {} kbps", bitrate / 1000);
    //             run_hardware(&mut capturer, w, h, enc);
    //             return;
    //         }
    //         Err(err) => println!("[capture] hardware encode unavailable ({err}) — using software"),
    //     }
    // }

    run_software(&mut capturer, w, h, bitrate, tx);
}

// ---- software path: openh264 (fallback / when no GPU encoder) ----
fn run_software(capturer: &mut Capturer, w: usize, h: usize, bitrate: u32, tx: Sender<Vec<u8>>) {
    let cfg = EncoderConfig::new()
        .usage_type(UsageType::ScreenContentRealTime)
        .rate_control_mode(RateControlMode::Bitrate)
        .set_bitrate_bps(bitrate)
        .max_frame_rate(30.0)
        .enable_skip_frame(true);
    let mut encoder = match Encoder::with_api_config(OpenH264API::from_source(), cfg) {
        Ok(e) => e,
        Err(e) => { eprintln!("[capture] encoder: {e}"); return; }
    };
    println!("[capture] encoder: software screen-content, {} kbps target", bitrate / 1000);
    let mut rgb = vec![0u8; w * h * 3];
    let mut n = 0u64;
    let mut have = false;
    let mut last_send = std::time::Instant::now();
    let mut last_key = std::time::Instant::now();
    let mut last_enc = std::time::Instant::now();
    let frame_interval = Duration::from_millis(33); // ~30 fps cap

    loop {
        match capturer.frame() {
            Ok(frame) => {
                if last_enc.elapsed() < frame_interval { thread::sleep(Duration::from_millis(2)); continue; }
                last_enc = std::time::Instant::now();
                let stride = frame.len() / h;
                if n == 0 { println!("[capture] first frame {} bytes, stride {stride} (row = {} px, expected {w})", frame.len(), stride / 4); }
                bgra_to_rgb(&frame, &mut rgb, w, h, stride);
                have = true;
                if n == 0 || last_key.elapsed() >= Duration::from_secs(2) { encoder.force_intra_frame(); last_key = std::time::Instant::now(); }
                if !encode_send(&mut encoder, &rgb, w, h, &tx) { break; }
                n += 1;
                last_send = std::time::Instant::now();
            }
            Err(ref e) if e.kind() == WouldBlock => {
                if have && last_send.elapsed() > Duration::from_millis(500) {
                    if last_key.elapsed() >= Duration::from_secs(2) { encoder.force_intra_frame(); last_key = std::time::Instant::now(); }
                    if !encode_send(&mut encoder, &rgb, w, h, &tx) { break; }
                    last_send = std::time::Instant::now();
                } else { thread::sleep(Duration::from_millis(8)); }
            }
            Err(e) => {
                eprintln!("[capture] error: {e} — recreating capturer");
                thread::sleep(Duration::from_millis(300));
                match Display::primary().and_then(Capturer::new) { Ok(c) => *capturer = c, Err(e2) => { eprintln!("[capture] recreate failed: {e2}"); break; } }
            }
        }
    }
}

// ---- hardware path: Media Foundation. Convert BGRA->NV12 and submit; the MF
// encoder thread emits H.264 into the same channel. (Currently disabled — see
// the commented block in run().) ----
#[cfg(windows)]
#[allow(dead_code)]
fn run_hardware(capturer: &mut Capturer, w: usize, h: usize, enc: crate::mf_encoder::MfEncoder) {
    let mut n = 0u64;
    let mut last_enc = std::time::Instant::now();
    let mut last_send = std::time::Instant::now();
    let mut last_nv12: Option<Vec<u8>> = None;
    let frame_interval = Duration::from_millis(33); // ~30 fps cap
    loop {
        match capturer.frame() {
            Ok(frame) => {
                if last_enc.elapsed() < frame_interval { thread::sleep(Duration::from_millis(2)); continue; }
                last_enc = std::time::Instant::now();
                let stride = frame.len() / h;
                if n == 0 { println!("[capture] first frame {} bytes, stride {stride}", frame.len()); }
                let mut nv12 = vec![0u8; w * h * 3 / 2];
                bgra_to_nv12(&frame, &mut nv12, w, h, stride);
                enc.submit(nv12.clone());
                last_nv12 = Some(nv12);
                last_send = std::time::Instant::now();
                n += 1;
            }
            Err(ref e) if e.kind() == WouldBlock => {
                // Static screen: keep the encoder fed so the stream stays warm.
                if let Some(f) = &last_nv12 {
                    if last_send.elapsed() > Duration::from_millis(500) {
                        enc.submit(f.clone());
                        last_send = std::time::Instant::now();
                    } else { thread::sleep(Duration::from_millis(8)); }
                } else { thread::sleep(Duration::from_millis(8)); }
            }
            Err(e) => {
                eprintln!("[capture] error: {e} — recreating capturer");
                thread::sleep(Duration::from_millis(300));
                match Display::primary().and_then(Capturer::new) { Ok(c) => *capturer = c, Err(e2) => { eprintln!("[capture] recreate failed: {e2}"); break; } }
            }
        }
    }
}

// BGRA -> NV12 (BT.601 limited range). Y at full res; one U,V per 2x2 block
// (sampled at the block's top-left pixel). Assumes even width/height.
#[cfg(windows)]
#[allow(dead_code)]
fn bgra_to_nv12(frame: &[u8], nv12: &mut [u8], w: usize, h: usize, stride: usize) {
    let (y_plane, uv_plane) = nv12.split_at_mut(w * h);
    for y in 0..h {
        for x in 0..w {
            let s = y * stride + x * 4;
            let b = frame[s] as i32;
            let g = frame[s + 1] as i32;
            let r = frame[s + 2] as i32;
            let yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            y_plane[y * w + x] = yy.clamp(0, 255) as u8;
        }
    }
    for cy in 0..h / 2 {
        for cx in 0..w / 2 {
            let s = (cy * 2) * stride + (cx * 2) * 4;
            let b = frame[s] as i32;
            let g = frame[s + 1] as i32;
            let r = frame[s + 2] as i32;
            let u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
            let v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
            let idx = cy * w + cx * 2;
            uv_plane[idx] = u.clamp(0, 255) as u8;
            uv_plane[idx + 1] = v.clamp(0, 255) as u8;
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
    use tokio::sync::mpsc::error::TrySendError;
    let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(rgb, (w, h)));
    match encoder.encode(&yuv) {
        Ok(bs) => match tx.try_send(bs.to_vec()) {
            Ok(_) => true,
            // Consumer is behind (network congestion) — drop this frame and keep
            // capturing rather than blocking the capture thread (a stall would
            // freeze the stream). The 2s keyframe recovers any lost reference.
            Err(TrySendError::Full(_)) => true,
            Err(TrySendError::Closed(_)) => false, // session ended
        },
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
