// Hardware H.264 encoder via Windows Media Foundation.
//
// Enumerates the GPU's async H.264 encoder MFT (NVENC / AMD AMF / Intel QSV),
// runs its event-driven pump on a dedicated thread, and emits raw H.264 that
// feeds the same WebRTC track the software (openh264) path uses. If no hardware
// encoder is available — or anything fails during setup — `spawn` returns Err
// and the caller falls back to software. It never blocks the capture thread.

#![cfg(windows)]
#![allow(dead_code)] // Phase 3 hardware encoder — implemented, currently disabled in capture.rs

use std::sync::mpsc::{Receiver, Sender};
use tokio::sync::mpsc::Sender as TokioSender;
use windows::core::Interface;
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};

/// Handle to the encoder thread. Submit NV12 frames; encoded H.264 is pushed to
/// the tokio channel the caller passed in (same one the software path uses).
pub struct MfEncoder {
    input_tx: Sender<Vec<u8>>,
    keyframe: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl MfEncoder {
    /// Try to start a hardware encoder. Errors if no HW encoder / setup fails.
    pub fn spawn(
        width: u32,
        height: u32,
        fps: u32,
        bitrate: u32,
        out_tx: TokioSender<Vec<u8>>,
    ) -> Result<MfEncoder, String> {
        let (input_tx, input_rx) = std::sync::mpsc::channel::<Vec<u8>>();
        let keyframe = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let kf = keyframe.clone();
        // Init happens on the encoder thread; report success/failure back.
        let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<(), String>>();
        std::thread::spawn(move || {
            match unsafe { encoder_thread(width, height, fps, bitrate, input_rx, out_tx, kf, &ready_tx) } {
                Ok(_) => {}
                Err(e) => { let _ = ready_tx.send(Err(e)); }
            }
        });
        match ready_rx.recv() {
            Ok(Ok(())) => Ok(MfEncoder { input_tx, keyframe }),
            Ok(Err(e)) => Err(e),
            Err(_) => Err("encoder thread died during init".into()),
        }
    }

    /// Hand a NV12 frame to the encoder (dropped if the encoder is behind).
    pub fn submit(&self, nv12: Vec<u8>) {
        let _ = self.input_tx.send(nv12);
    }

    pub fn request_keyframe(&self) {
        self.keyframe.store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

fn e<T: std::fmt::Debug>(ctx: &str, r: T) -> String {
    format!("{ctx}: {r:?}")
}

const MF_E_TRANSFORM_NEED_MORE_INPUT: windows::core::HRESULT = windows::core::HRESULT(0xC00D6D72u32 as i32);

unsafe fn encoder_thread(
    width: u32,
    height: u32,
    fps: u32,
    bitrate: u32,
    input_rx: Receiver<Vec<u8>>,
    out_tx: TokioSender<Vec<u8>>,
    keyframe: std::sync::Arc<std::sync::atomic::AtomicBool>,
    ready_tx: &Sender<Result<(), String>>,
) -> Result<(), String> {
    let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(|r| e("MFStartup", r))?;

    // Enumerate the hardware H.264 encoder.
    let out_info = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_H264 };
    let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
    let mut count: u32 = 0;
    MFTEnumEx(
        MFT_CATEGORY_VIDEO_ENCODER,
        MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER,
        None,
        Some(&out_info),
        &mut activates,
        &mut count,
    ).map_err(|r| e("MFTEnumEx", r))?;
    if count == 0 || activates.is_null() {
        return Err("no hardware H.264 encoder found".into());
    }
    let list = std::slice::from_raw_parts(activates, count as usize);
    let activate = list[0].clone().ok_or_else(|| "null activate".to_string())?;
    let transform: IMFTransform = activate.ActivateObject().map_err(|r| e("ActivateObject", r))?;

    // Async unlock (hardware MFTs are asynchronous).
    let attrs = transform.GetAttributes().map_err(|r| e("GetAttributes", r))?;
    attrs.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1).map_err(|r| e("async unlock", r))?;

    // Output type FIRST (required for the H.264 encoder), then input.
    let frame_size = ((width as u64) << 32) | height as u64;
    let frame_rate = ((fps as u64) << 32) | 1u64;

    let out_type = MFCreateMediaType().map_err(|r| e("MFCreateMediaType out", r))?;
    out_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video).ok();
    out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264).ok();
    out_type.SetUINT32(&MF_MT_AVG_BITRATE, bitrate).ok();
    out_type.SetUINT64(&MF_MT_FRAME_SIZE, frame_size).ok();
    out_type.SetUINT64(&MF_MT_FRAME_RATE, frame_rate).ok();
    out_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32).ok();
    out_type.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base.0 as u32).ok();
    out_type.SetUINT32(&MF_MT_MAX_KEYFRAME_SPACING, fps.saturating_mul(2)).ok(); // ~2s IDR
    transform.SetOutputType(0, &out_type, 0).map_err(|r| e("SetOutputType", r))?;

    let in_type = MFCreateMediaType().map_err(|r| e("MFCreateMediaType in", r))?;
    in_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video).ok();
    in_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12).ok();
    in_type.SetUINT64(&MF_MT_FRAME_SIZE, frame_size).ok();
    in_type.SetUINT64(&MF_MT_FRAME_RATE, frame_rate).ok();
    in_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32).ok();
    transform.SetInputType(0, &in_type, 0).map_err(|r| e("SetInputType", r))?;

    let event_gen: IMFMediaEventGenerator = transform.cast().map_err(|r| e("cast event gen", r))?;
    transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0).ok();
    transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0).ok();

    // Setup succeeded.
    let _ = ready_tx.send(Ok(()));

    let mut sample_time: i64 = 0;
    let sample_dur: i64 = 10_000_000 / fps.max(1) as i64; // 100ns units

    loop {
        // Blocking wait for the next NeedInput / HaveOutput event.
        let event = match event_gen.GetEvent(MEDIA_EVENT_GENERATOR_GET_EVENT_FLAGS(0)) {
            Ok(ev) => ev,
            Err(_) => break,
        };
        let met = event.GetType().unwrap_or(0);
        if met == METransformNeedInput.0 as u32 {
            // Pull the next NV12 frame (block until one arrives or the channel closes).
            let nv12 = match input_rx.recv() {
                Ok(f) => f,
                Err(_) => break, // capture ended
            };
            // Keyframes are scheduled by the encoder (MAX_KEYFRAME_SPACING); just
            // clear any request flag.
            let _ = keyframe.swap(false, std::sync::atomic::Ordering::Relaxed);
            if let Err(err) = feed(&transform, &nv12, sample_time, sample_dur) {
                eprintln!("[mf] feed error: {err}");
            }
            sample_time += sample_dur;
        } else if met == METransformHaveOutput.0 as u32 {
            match drain(&transform) {
                Ok(Some(bytes)) => { let _ = out_tx.try_send(bytes); }
                Ok(None) => {}
                Err(err) => eprintln!("[mf] drain error: {err}"),
            }
        }
    }

    transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0).ok();
    transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0).ok();
    Ok(())
}

// Wrap a NV12 buffer in an IMFSample and push it into the encoder.
unsafe fn feed(transform: &IMFTransform, nv12: &[u8], time: i64, dur: i64) -> Result<(), String> {
    let sample = MFCreateSample().map_err(|r| e("MFCreateSample", r))?;
    let buffer = MFCreateMemoryBuffer(nv12.len() as u32).map_err(|r| e("MFCreateMemoryBuffer", r))?;
    let mut ptr: *mut u8 = std::ptr::null_mut();
    let mut maxlen = 0u32;
    buffer.Lock(&mut ptr, Some(&mut maxlen), None).map_err(|r| e("buffer Lock", r))?;
    std::ptr::copy_nonoverlapping(nv12.as_ptr(), ptr, nv12.len());
    buffer.Unlock().ok();
    buffer.SetCurrentLength(nv12.len() as u32).ok();
    sample.AddBuffer(&buffer).map_err(|r| e("AddBuffer", r))?;
    sample.SetSampleTime(time).ok();
    sample.SetSampleDuration(dur).ok();
    transform.ProcessInput(0, &sample, 0).map_err(|r| e("ProcessInput", r))?;
    Ok(())
}

// Pull one encoded H.264 access unit out of the encoder.
unsafe fn drain(transform: &IMFTransform) -> Result<Option<Vec<u8>>, String> {
    let stream_info = transform.GetOutputStreamInfo(0).unwrap_or_default();
    let provides = (stream_info.dwFlags
        & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32)) != 0;

    let mut out_sample: Option<IMFSample> = None;
    if !provides {
        let s = MFCreateSample().map_err(|r| e("out MFCreateSample", r))?;
        let b = MFCreateMemoryBuffer(stream_info.cbSize.max(1 << 20)).map_err(|r| e("out buffer", r))?;
        s.AddBuffer(&b).ok();
        out_sample = Some(s);
    }

    let mut out_buf = MFT_OUTPUT_DATA_BUFFER {
        dwStreamID: 0,
        pSample: std::mem::ManuallyDrop::new(out_sample.clone()),
        dwStatus: 0,
        pEvents: std::mem::ManuallyDrop::new(None),
    };
    let mut status = 0u32;
    let hr = transform.ProcessOutput(0, std::slice::from_mut(&mut out_buf), &mut status);
    if let Err(err) = hr {
        if err.code() == MF_E_TRANSFORM_NEED_MORE_INPUT {
            return Ok(None);
        }
        return Err(e("ProcessOutput", err));
    }
    let sample = std::mem::ManuallyDrop::take(&mut out_buf.pSample);
    let sample = match sample { Some(s) => s, None => return Ok(None) };
    let buffer = sample.ConvertToContiguousBuffer().map_err(|r| e("ConvertToContiguous", r))?;
    let mut ptr: *mut u8 = std::ptr::null_mut();
    let mut len = 0u32;
    buffer.Lock(&mut ptr, None, Some(&mut len)).map_err(|r| e("out Lock", r))?;
    let bytes = std::slice::from_raw_parts(ptr, len as usize).to_vec();
    buffer.Unlock().ok();
    Ok(Some(bytes))
}
