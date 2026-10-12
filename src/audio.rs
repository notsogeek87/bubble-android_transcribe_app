//! Small audio helpers shared between the engine and the subtitle pipeline.

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + WIN <= to {
        let energy: f32 = samples[i..i + WIN].iter().map(|&x| x * x).sum();
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        i += WIN / 2;
    }
    best_pos
}

/// Analysis frame for [`trim_silence`] (20 ms at 16 kHz).
const TRIM_FRAME: usize = 320;
/// Context kept around the detected speech so soft onsets/endings survive.
const TRIM_PAD_FRAMES: usize = 20; // 400 ms
/// Absolute RMS floor for the speech threshold, so digital silence can't
/// make every noise bump look like speech.
const TRIM_MIN_RMS: f32 = 0.008;
/// Speech must be this many times louder than the background noise.
const TRIM_NOISE_RATIO: f32 = 3.0;
/// Speech onset = at least `TRIM_SUSTAIN_MIN` loud frames out of
/// `TRIM_SUSTAIN_FRAMES` consecutive ones (80 ms of 100 ms).
const TRIM_SUSTAIN_FRAMES: usize = 5;
const TRIM_SUSTAIN_MIN: usize = 4;
/// Don't bother cutting less than this (0.5 s): not worth the risk.
const TRIM_MIN_SAVING: usize = 8_000;

/// Mirror of the onset test for the end of the speech.
fn sustained_back(voiced: &[bool], i: usize) -> bool {
    let from = (i + 1).saturating_sub(TRIM_SUSTAIN_FRAMES);
    voiced[from..=i].iter().filter(|&&v| v).count() >= TRIM_SUSTAIN_MIN
}

/// Drops leading and trailing silence from a dictation clip.
///
/// Inference time grows with clip length, and a dictation always carries
/// silence it doesn't need: the pause before the first word and, with
/// auto-stop, the ~2 s of silence that triggers the stop. The threshold adapts
/// to the clip's own background noise (the quietest fifth of the frames), so
/// a noisy car cabin isn't mistaken for speech. Only the ends are trimmed —
/// pauses inside the speech are left alone — and the clip is returned
/// untouched when no speech is found or the saving is negligible.
pub fn trim_silence(samples: Vec<f32>) -> Vec<f32> {
    let frames: Vec<f32> = samples
        .chunks(TRIM_FRAME)
        .map(|c| (c.iter().map(|&x| x * x).sum::<f32>() / c.len() as f32).sqrt())
        .collect();
    if frames.len() <= 2 * TRIM_PAD_FRAMES {
        return samples;
    }

    let mut sorted = frames.clone();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let quiet = &sorted[..(sorted.len() / 5).max(1)];
    let noise = quiet.iter().sum::<f32>() / quiet.len() as f32;
    let threshold = (noise * TRIM_NOISE_RATIO).max(TRIM_MIN_RMS);

    // A lone click (the tap that starts the recording, a pop on mic open) is
    // louder than the threshold for a frame or two. Treating it as speech
    // keeps it as the clip's "content" and Whisper then hallucinates a word
    // ("Yeah.") from the click plus silence. Speech must be sustained.
    let voiced: Vec<bool> = frames.iter().map(|&r| r > threshold).collect();
    let sustained = |i: usize| {
        voiced[i] && voiced[i..(i + TRIM_SUSTAIN_FRAMES).min(voiced.len())]
            .iter()
            .filter(|&&v| v)
            .count()
            >= TRIM_SUSTAIN_MIN
    };
    let first = (0..voiced.len()).find(|&i| sustained(i));
    let last = (0..voiced.len())
        .rev()
        .find(|&i| voiced[i] && sustained_back(&voiced, i));
    let (first, last) = match (first, last) {
        (Some(f), Some(l)) => (f, l),
        _ => return samples,
    };

    let start = first.saturating_sub(TRIM_PAD_FRAMES) * TRIM_FRAME;
    let end = ((last + 1 + TRIM_PAD_FRAMES) * TRIM_FRAME).min(samples.len());
    if start + (samples.len() - end) < TRIM_MIN_SAVING {
        return samples;
    }
    samples[start..end].to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `secs` of a 200 Hz tone at `amp`, 16 kHz.
    fn tone(secs: f32, amp: f32) -> Vec<f32> {
        (0..(secs * 16_000.0) as usize)
            .map(|i| amp * (i as f32 * 200.0 * std::f32::consts::TAU / 16_000.0).sin())
            .collect()
    }

    fn clip(lead: f32, speech: f32, trail: f32, noise: f32) -> Vec<f32> {
        let mut v = tone(lead, noise);
        v.extend(tone(speech, 0.3));
        v.extend(tone(trail, noise));
        v
    }

    #[test]
    fn trims_leading_and_trailing_silence() {
        let out = trim_silence(clip(2.0, 3.0, 2.0, 0.001));
        let secs = out.len() as f32 / 16_000.0;
        // 3 s of speech + 0.4 s padding on each side.
        assert!((secs - 3.8).abs() < 0.1, "got {secs}");
    }

    #[test]
    fn keeps_all_speech() {
        let out = trim_silence(clip(2.0, 3.0, 2.0, 0.001));
        let loud = out.iter().filter(|&&x| x.abs() > 0.2).count();
        assert!(loud as f32 / 16_000.0 > 0.5 * 3.0 * 0.9);
    }

    #[test]
    fn leaves_internal_pauses() {
        let mut v = tone(1.0, 0.001);
        v.extend(tone(1.0, 0.3));
        v.extend(tone(3.0, 0.001)); // long pause inside
        v.extend(tone(1.0, 0.3));
        v.extend(tone(1.0, 0.001));
        let out = trim_silence(v);
        let secs = out.len() as f32 / 16_000.0;
        assert!(secs > 5.0 + 0.7, "got {secs}"); // 1+3+1 plus padding
    }

    #[test]
    fn noisy_cabin_is_not_speech() {
        // Constant noise with speech clearly louder: ends still trimmed.
        let out = trim_silence(clip(2.0, 3.0, 2.0, 0.02));
        assert!(out.len() < 6 * 16_000);
    }

    #[test]
    fn leading_click_is_not_speech() {
        // 30 ms click, 2 s of silence, then real speech: the click must be
        // trimmed away rather than anchoring the start of the clip.
        let mut v = tone(0.03, 0.4);
        v.extend(tone(2.0, 0.001));
        v.extend(tone(3.0, 0.3));
        v.extend(tone(2.0, 0.001));
        let out = trim_silence(v);
        let secs = out.len() as f32 / 16_000.0;
        assert!((secs - 3.8).abs() < 0.1, "got {secs}");
    }

    #[test]
    fn untouched_when_no_speech_or_short() {
        let silence = tone(5.0, 0.001);
        assert_eq!(trim_silence(silence.clone()).len(), silence.len());
        let short = clip(0.1, 0.3, 0.1, 0.001);
        assert_eq!(trim_silence(short.clone()).len(), short.len());
    }

    #[test]
    fn untouched_when_saving_is_negligible() {
        let v = clip(0.2, 3.0, 0.2, 0.001);
        assert_eq!(trim_silence(v.clone()).len(), v.len());
    }
}
