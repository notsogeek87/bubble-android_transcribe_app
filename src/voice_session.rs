use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::engine;

// --- Optional auto-stop endpointing (same level heuristics as recog_service) --
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.06;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.04;
/// The noise floor never climbs above this, so a loud room can't make speech
/// look like silence.
const MAX_NOISE_FLOOR: f32 = 0.08;
/// Trailing silence after speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// Speech/silence tracking shared between the audio callback and the
/// auto-stop monitor thread.
struct Endpointing {
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    speech_started: AtomicBool,
}

// --- Live (streaming) transcription while recording --------------------------
/// How often the live thread inspects the recording.
const LIVE_POLL_MS: u64 = 100;
/// Minimum new audio between two partial updates (~0.7 s).
const LIVE_TICK_SAMPLES: usize = 11_200;
/// Trailing silence that finalizes the current segment (~0.7 s).
const LIVE_FINALIZE_SILENCE: usize = 11_200;
/// Hard cap on one segment; partials re-transcribe the whole segment, so this
/// also bounds their cost.
const LIVE_MAX_SEGMENT: usize = 10 * 16_000;
/// Segments shorter than this are not worth transcribing (0.5 s).
const LIVE_MIN_SEGMENT: usize = 8_000;
/// Audio kept before detected speech so the first word isn't clipped.
const LIVE_PREROLL: usize = 6_400;
/// 100 ms analysis window.
const LIVE_WIN: usize = 1_600;
/// Window RMS at or above this counts as speech.
const LIVE_SPEECH_RMS: f32 = 0.01;

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
    /// Samples of `audio_buffer` already committed by the live thread.
    pub committed: Arc<AtomicUsize>,
    /// Live transcription thread of the current recording, if any.
    pub live_handle: Option<std::thread::JoinHandle<()>>,
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

fn notify_partial(env: &mut JNIEnv, obj: &JObject, text: &str, is_final: bool) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onPartialText",
            "(Ljava/lang/String;Z)V",
            &[(&jtxt).into(), is_final.into()],
        );
        let _ = env.exception_clear();
    }
}

fn window_rms(w: &[f32]) -> f32 {
    (w.iter().map(|&x| x * x).sum::<f32>() / w.len().max(1) as f32).sqrt()
}

/// Streams text while the user is still speaking: finished sentences are
/// delivered as final, the sentence in progress as a replaceable partial.
/// Whatever is left when recording stops is transcribed by `stop_recording`
/// from `committed` onward.
fn live_loop(
    jvm: Arc<jni::JavaVM>,
    target_ref: GlobalRef,
    buffer: Arc<Mutex<Vec<f32>>>,
    active: Arc<AtomicBool>,
    committed: Arc<AtomicUsize>,
) {
    let mut env = match jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let obj = target_ref.as_obj();
    let mut last_job_len = 0usize;
    // Extra audio to wait for before the next partial, so a slow device
    // spends its time on finals instead of falling behind.
    let mut cooldown = LIVE_TICK_SAMPLES;

    while active.load(Ordering::SeqCst) {
        std::thread::sleep(Duration::from_millis(LIVE_POLL_MS));
        if !active.load(Ordering::SeqCst) {
            break;
        }
        let start = committed.load(Ordering::SeqCst);
        let seg: Vec<f32> = {
            let b = buffer.lock().unwrap();
            if b.len() <= start {
                continue;
            }
            b[start..].to_vec()
        };

        let wins: Vec<f32> = seg.chunks(LIVE_WIN).map(window_rms).collect();
        let first_speech = wins.iter().position(|&r| r >= LIVE_SPEECH_RMS);
        let Some(first) = first_speech else {
            // Only silence so far: skip it, keeping a little pre-roll.
            let skip = seg.len().saturating_sub(LIVE_PREROLL);
            committed.store(start + skip, Ordering::SeqCst);
            last_job_len = 0;
            continue;
        };
        if first * LIVE_WIN > LIVE_PREROLL {
            // Drop the leading silence before the speech.
            let skip = first * LIVE_WIN - LIVE_PREROLL;
            committed.store(start + skip, Ordering::SeqCst);
            last_job_len = 0;
            continue;
        }

        let silence: usize = wins
            .iter()
            .rev()
            .take_while(|&&r| r < LIVE_SPEECH_RMS)
            .count()
            * LIVE_WIN;
        let by_silence = silence >= LIVE_FINALIZE_SILENCE;
        let by_length = seg.len() >= LIVE_MAX_SEGMENT;

        let (samples, is_final, consumed) = if by_silence || by_length {
            if seg.len() < LIVE_MIN_SEGMENT {
                continue;
            }
            let cut = if by_silence {
                seg.len()
            } else {
                // Forced cut mid-speech: split at the quietest point of the
                // last seconds so no word is chopped in half.
                let from = seg.len().saturating_sub(3 * 16_000);
                crate::audio::find_quietest_split(&seg, from, seg.len())
            };
            (seg[..cut].to_vec(), true, cut)
        } else if seg.len() >= LIVE_MIN_SEGMENT
            && seg.len().saturating_sub(last_job_len) >= cooldown
        {
            (seg.clone(), false, 0)
        } else {
            continue;
        };

        let Some(eng) = engine::get_engine() else { continue };
        let started = Instant::now();
        let res = engine::transcribe_shared(&eng, crate::audio::trim_silence(samples));
        let elapsed = started.elapsed().as_secs_f32();

        if !active.load(Ordering::SeqCst) {
            // Stopped while transcribing: stop_recording redoes this audio.
            break;
        }
        if is_final {
            committed.store(start + consumed, Ordering::SeqCst);
            last_job_len = 0;
        } else {
            last_job_len = seg.len();
            // Wait for at least as much new audio as this job took to run.
            cooldown = ((elapsed.max(0.7)) * 16_000.0) as usize;
        }
        if let Ok(text) = res {
            let text = text.trim();
            if !text.is_empty() || is_final {
                notify_partial(&mut env, obj, text, is_final);
            }
        }
    }
}

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
        committed: Arc::new(AtomicUsize::new(0)),
        live_handle: None,
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

/// Begin microphone capture. With `auto_stop` set, a monitor thread watches
/// for trailing silence after speech (or a no-speech timeout) and invokes the
/// Java-side `onAutoStop()` callback, which is expected to stop the recording
/// the same way a manual tap would.
pub fn start_recording(
    mut env: JNIEnv,
    state: &mut VoiceSessionState,
    auto_stop: bool,
    live: bool,
) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    state.audio_buffer.lock().unwrap().clear();
    state.committed.store(0, Ordering::SeqCst);
    let buffer_clone = state.audio_buffer.clone();

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Endpointing {
            last_voice: Mutex::new(Instant::now()),
            noise_floor: Mutex::new(0.0),
            speech_started: AtomicBool::new(false),
        }))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                let floor = *ep.noise_floor.lock().unwrap();
                let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;
                if is_speech {
                    *ep.last_voice.lock().unwrap() = Instant::now();
                    ep.speech_started.store(true, Ordering::SeqCst);
                } else {
                    // Track the noise floor: follow drops quickly, rise only very slowly
                    // (pauses between words would otherwise ratchet it up to speech level
                    // and end the session mid-sentence).
                    let mut nf = ep.noise_floor.lock().unwrap();
                    *nf = if level < *nf {
                        *nf * 0.8 + level * 0.2
                    } else {
                        (*nf * 0.999 + level * 0.001).min(MAX_NOISE_FLOOR)
                    };
                }
            }

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            if live {
                let (jvm, target_ref) = (state.jvm.clone(), state.target_ref.clone());
                let (buf, act, com) = (
                    state.audio_buffer.clone(),
                    session_active.clone(),
                    state.committed.clone(),
                );
                state.live_handle = Some(std::thread::spawn(move || {
                    live_loop(jvm, target_ref, buf, act, com)
                }));
            }

            if let Some(ep) = endpoint {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let speech = ep.speech_started.load(Ordering::SeqCst);
                    let silence = ep.last_voice.lock().unwrap().elapsed();
                    let done = (speech
                        && silence >= Duration::from_millis(AUTO_STOP_SILENCE_MS))
                        || (!speech
                            && started_at.elapsed()
                                >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ = env.call_method(
                                    target_ref.as_obj(),
                                    "onAutoStop",
                                    "()V",
                                    &[],
                                );
                            }
                        }
                        return;
                    }
                });
            }
        }
        Err(e) => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Drop the stream to stop recording; end the auto-stop monitor if running.
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;

    let mut buffer = state.audio_buffer.lock().unwrap().clone();
    let live_handle = state.live_handle.take();
    let committed = state.committed.clone();
    let is_live = live_handle.is_some();

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    notify_status(&mut env, target_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Let an in-flight live job finish, then transcribe only what it has
        // not already committed.
        if let Some(h) = live_handle {
            let _ = h.join();
        }
        if is_live {
            // Read after the join so we see everything the live thread
            // committed; `buffer` was cloned at stop time, offsets still match.
            let done = committed.load(Ordering::SeqCst).min(buffer.len());
            buffer.drain(..done);
        }

        // Wait for engine if somehow still loading
        if engine::get_engine().is_none() {
            if let Err(_) = engine::ensure_loaded(&mut env, obj) {
                return;
            }
        }

        // A live session whose remaining audio is only silence has nothing
        // left to say; the text was already delivered as finals.
        if is_live && buffer.iter().all(|&x| x.abs() < 0.01) {
            notify_status(&mut env, obj, "Ready");
            notify_text(&mut env, obj, "");
            return;
        }

        if let Some(eng_arc) = engine::get_engine() {
            let res = engine::transcribe_shared(&eng_arc, crate::audio::trim_silence(buffer));

            match res {
                Ok(text) => {
                    notify_status(&mut env, obj, "Ready");
                    notify_text(&mut env, obj, &text);
                }
                Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
            }
        } else {
            notify_status(&mut env, obj, "Error: model not loaded");
        }
    });
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    state.live_handle = None;
    state.audio_buffer.lock().unwrap().clear();
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}
