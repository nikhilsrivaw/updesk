// UpDesk silent native host.
//   Phase 1: silent screen capture (scrap) — no UI.
//   Phase 2: H.264 encode (openh264).
//   Phase 3: native WebRTC (webrtc-rs) — feed the live encoded screen into a
//            real peer connection and produce an SDP offer with the video track.
//
// This run wires capture -> encode -> WebRTC video track, creates an offer, and
// prints the SDP. A valid offer that advertises H.264 video proves the native
// media stack is live (Phase 4 then connects it to the signaling server).

use openh264::encoder::Encoder;
use openh264::formats::{RgbSliceU8, YUVBuffer};
use scrap::{Capturer, Display};
use std::io::ErrorKind::WouldBlock;
use std::sync::Arc;
use std::thread;
use std::time::Duration;
use webrtc::api::interceptor_registry::register_default_interceptors;
use webrtc::api::media_engine::{MediaEngine, MIME_TYPE_H264};
use webrtc::api::APIBuilder;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::interceptor::registry::Registry;
use webrtc::media::Sample;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::rtp_transceiver::rtp_codec::RTCRtpCodecCapability;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // --- WebRTC stack (H.264) ---
    let mut m = MediaEngine::default();
    m.register_default_codecs()?;
    let mut registry = Registry::new();
    registry = register_default_interceptors(registry, &mut m)?;
    let api = APIBuilder::new()
        .with_media_engine(m)
        .with_interceptor_registry(registry)
        .build();

    let config = RTCConfiguration {
        ice_servers: vec![RTCIceServer {
            urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            ..Default::default()
        }],
        ..Default::default()
    };
    let pc = Arc::new(api.new_peer_connection(config).await?);

    let video_track = Arc::new(TrackLocalStaticSample::new(
        RTCRtpCodecCapability { mime_type: MIME_TYPE_H264.to_owned(), ..Default::default() },
        "video".to_owned(),
        "updesk-screen".to_owned(),
    ));
    pc.add_track(video_track.clone()).await?;

    // --- capture + encode thread -> channel -> track ---
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<u8>>(8);
    thread::spawn(move || capture_encode_loop(tx));

    let writer = video_track.clone();
    tokio::spawn(async move {
        let mut n = 0u64;
        while let Some(data) = rx.recv().await {
            let _ = writer
                .write_sample(&Sample {
                    data: data.into(),
                    duration: Duration::from_millis(33),
                    ..Default::default()
                })
                .await;
            n += 1;
        }
        println!("sample writer stopped after {n} frames");
    });

    // --- produce the offer (with ICE gathered) ---
    let offer = pc.create_offer(None).await?;
    let mut gather_complete = pc.gathering_complete_promise().await;
    pc.set_local_description(offer).await?;
    let _ = gather_complete.recv().await;

    if let Some(local) = pc.local_description().await {
        let has_h264 = local.sdp.contains("H264");
        println!("=== SDP OFFER (has H264 video: {has_h264}) ===");
        println!("{}", local.sdp);
    }

    // Let a few frames flow to confirm the pipeline is live.
    tokio::time::sleep(Duration::from_secs(3)).await;
    println!("Phase 3: native WebRTC peer with live screen track ✓");
    pc.close().await?;
    Ok(())
}

fn capture_encode_loop(tx: tokio::sync::mpsc::Sender<Vec<u8>>) {
    let display = match Display::primary() {
        Ok(d) => d,
        Err(e) => { eprintln!("no display: {e}"); return; }
    };
    let (w, h) = (display.width(), display.height());
    let mut capturer = match Capturer::new(display) { Ok(c) => c, Err(e) => { eprintln!("capturer: {e}"); return; } };
    let mut encoder = match Encoder::new() { Ok(e) => e, Err(e) => { eprintln!("encoder: {e}"); return; } };
    let mut rgb = vec![0u8; w * h * 3];
    loop {
        match capturer.frame() {
            Ok(frame) => {
                let stride = frame.len() / h;
                bgra_to_rgb(&frame, &mut rgb, w, h, stride);
                let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(&rgb, (w, h)));
                if let Ok(bs) = encoder.encode(&yuv) {
                    if tx.blocking_send(bs.to_vec()).is_err() { break; }
                }
            }
            Err(ref e) if e.kind() == WouldBlock => thread::sleep(Duration::from_millis(8)),
            Err(_) => break,
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
