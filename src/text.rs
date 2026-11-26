//! Post-processing of model output.

/// Longest phrase (in words) checked for looping repetitions.
const MAX_LOOP_WORDS: usize = 12;

/// Comparison key of a word: lowercase, without surrounding punctuation, so
/// "bien," and "Bien." count as the same word.
fn key(word: &str) -> String {
    let trimmed = word.trim_matches(|c: char| !c.is_alphanumeric());
    if trimmed.is_empty() { word } else { trimmed }.to_lowercase()
}

/// Removes decoding loops — the same word or phrase emitted over and over
/// ("et puis et puis et puis et puis…"), the typical failure of a model that
/// lost track of the audio. A word may appear at most 3 times in a row
/// ("non non non") and a phrase of several words at most twice; further
/// consecutive copies are dropped. Text without such loops is returned
/// unchanged (word spacing included).
pub fn collapse_repetitions(text: &str) -> String {
    let words: Vec<&str> = text.split_whitespace().collect();
    if words.len() < 3 {
        return text.to_string();
    }
    let mut out: Vec<&str> = Vec::with_capacity(words.len());
    let mut keys: Vec<String> = Vec::with_capacity(words.len());
    let mut dropped = false;
    for &w in &words {
        out.push(w);
        keys.push(key(w));
        for n in 1..=MAX_LOOP_WORDS {
            let max_copies = if n == 1 { 3 } else { 2 };
            let span = n * (max_copies + 1);
            if keys.len() < span {
                break;
            }
            let tail = &keys[keys.len() - span..];
            let last = &tail[span - n..];
            if tail.chunks(n).all(|c| c == last) {
                out.truncate(out.len() - n);
                keys.truncate(keys.len() - n);
                dropped = true;
                break;
            }
        }
    }
    if dropped {
        log::warn!("removed a repetition loop from the transcription");
        out.join(" ")
    } else {
        text.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_text_untouched() {
        let t = "Bonjour, je voudrais réserver une table pour deux personnes ce soir.";
        assert_eq!(collapse_repetitions(t), t);
        let t = "non non non, c'est bien, c'est bien.";
        assert_eq!(collapse_repetitions(t), t);
    }

    #[test]
    fn word_loop_collapsed() {
        assert_eq!(
            collapse_repetitions("je pense que que que que que que c'est ça"),
            "je pense que que que c'est ça"
        );
    }

    #[test]
    fn phrase_loop_collapsed() {
        let t = "on se voit demain. Merci. Merci. Merci. Merci. Merci.";
        assert_eq!(collapse_repetitions(t), "on se voit demain. Merci. Merci. Merci.");
        let t = "et puis on y va et puis on y va et puis on y va et puis on y va fin";
        assert_eq!(collapse_repetitions(t), "et puis on y va et puis on y va fin");
    }
}
