//! User vocabulary: spelling corrections applied to the transcribed text.
//!
//! The speech model is fixed, so names and nicknames it keeps getting wrong
//! are fixed afterwards. The rules live in `vocabulary.txt` in filesDir (edited
//! from the app settings), one per line:
//!
//! * `heard => wanted` — replace `heard` with `wanted`;
//! * `Wanted` alone — fix the casing/accents of that word wherever it is
//!   recognised in any case.
//!
//! Matching ignores case and only hits whole words. Empty lines and lines
//! starting with `#` are ignored.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use once_cell::sync::Lazy;

/// File in filesDir holding the rules.
pub const VOCABULARY_FILE: &str = "vocabulary.txt";

static VOCABULARY_PATH: Lazy<Mutex<Option<PathBuf>>> = Lazy::new(|| Mutex::new(None));

/// Remembers where the rules file is (set once the engine loads).
pub fn set_path(path: PathBuf) {
    *VOCABULARY_PATH.lock().unwrap_or_else(|p| p.into_inner()) = Some(path);
}

/// Applies the user's rules to `text`. The file is re-read on every call, so
/// edits take effect immediately and in every process.
pub fn apply_configured(text: String) -> String {
    let path = VOCABULARY_PATH
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone();
    let Some(path) = path else { return text };
    match std::fs::read_to_string(&path) {
        Ok(rules) => apply(&text, &parse(&rules)),
        Err(_) => text,
    }
}

/// A single rule: the words to look for and what to write instead.
pub struct Rule {
    from: Vec<char>,
    to: String,
}

fn parse(rules: &str) -> Vec<Rule> {
    let mut out: Vec<Rule> = rules
        .lines()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                return None;
            }
            let (from, to) = match line.split_once("=>") {
                Some((f, t)) => (f.trim(), t.trim()),
                None => (line, line),
            };
            if from.is_empty() {
                return None;
            }
            Some(Rule {
                from: from.chars().collect(),
                to: to.to_string(),
            })
        })
        .collect();
    // Longest first, so "ma femme" wins over "ma".
    out.sort_by_key(|r| std::cmp::Reverse(r.from.len()));
    out
}

fn fold(c: char) -> char {
    c.to_lowercase().next().unwrap_or(c)
}

fn is_word(c: char) -> bool {
    c.is_alphanumeric()
}

/// Length of the match of `rule` at `text[at..]`, if any. A space in the rule
/// matches any run of whitespace.
fn match_at(text: &[char], at: usize, rule: &[char]) -> Option<usize> {
    let mut i = at;
    let mut j = 0;
    while j < rule.len() {
        let r = rule[j];
        if r.is_whitespace() {
            if i >= text.len() || !text[i].is_whitespace() {
                return None;
            }
            while i < text.len() && text[i].is_whitespace() {
                i += 1;
            }
            while j < rule.len() && rule[j].is_whitespace() {
                j += 1;
            }
        } else {
            if i >= text.len() || fold(text[i]) != fold(r) {
                return None;
            }
            i += 1;
            j += 1;
        }
    }
    // Whole words only.
    if i < text.len() && is_word(text[i]) && is_word(rule[rule.len() - 1]) {
        return None;
    }
    Some(i - at)
}

pub fn apply(text: &str, rules: &[Rule]) -> String {
    if rules.is_empty() {
        return text.to_string();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    'outer: while i < chars.len() {
        let at_word_start = i == 0 || !is_word(chars[i - 1]) || !is_word(chars[i]);
        if at_word_start {
            for rule in rules {
                if let Some(len) = match_at(&chars, i, &rule.from) {
                    out.push_str(&rule.to);
                    i += len;
                    continue 'outer;
                }
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

/// Path helper for callers that only have the files dir.
pub fn path_in(files_dir: &Path) -> PathBuf {
    files_dir.join(VOCABULARY_FILE)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(rules: &str, text: &str) -> String {
        apply(text, &parse(rules))
    }

    #[test]
    fn replaces_whole_words_ignoring_case() {
        assert_eq!(run("sandrine => Sandrine", "dis à sandrine que"), "dis à Sandrine que");
        assert_eq!(run("sandrine => Sandrine", "SANDRINE arrive"), "Sandrine arrive");
        assert_eq!(run("bou => Bou", "bouger"), "bouger");
    }

    #[test]
    fn bare_word_fixes_spelling() {
        assert_eq!(run("Séverine", "appelle séverine, stp"), "appelle Séverine, stp");
    }

    #[test]
    fn multi_word_and_punctuation() {
        assert_eq!(run("ma  femme => Léa", "avec ma femme."), "avec Léa.");
        assert_eq!(run("sa va => ça va", "Sa   va bien, sa va"), "ça va bien, ça va");
    }

    #[test]
    fn apostrophe_is_a_boundary() {
        assert_eq!(run("alexandre => Alexandre", "l'alexandre"), "l'Alexandre");
    }

    #[test]
    fn longest_rule_wins_and_comments_ignored() {
        assert_eq!(run("# note\nma => X\nma femme => Léa", "ma femme"), "Léa");
        assert_eq!(run("", "rien"), "rien");
    }
}
