//! Local custom-vocabulary post-ASR correction.
//!
//! Starting point: Handy `apply_custom_words` (MIT, cjpais/Handy
//! src-tauri/src/audio_toolkit/text.rs) — Levenshtein, Soundex, ≤3-word
//! n-grams. Thresholds are **not** copied: Handy's 0.5 is too loose for
//! short common words. The UI is a word list. Matching always runs
//! exact aliases (user + derived acronym splits) then conservative fuzzy.
//! Ordinary stoplist words are never fuzzy-replaced.
//!
//! Not applied inside `engine::transcribe_shared` (subtitles / file
//! transcription must stay raw).

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

pub const VOCAB_FILE: &str = "vocab.json";
pub const DEFAULT_THRESHOLD: f64 = 0.18;

/// Words that must never be Soundex/Levenshtein-replaced. Aliases still win.
const STOPLIST: &[&str] = &[
    "a", "an", "the", "to", "of", "in", "on", "for", "and", "or", "is", "it", "be",
    "go", "no", "so", "my", "me", "we", "you", "i", "this", "that", "with", "from",
    "at", "as", "by", "not", "but", "if", "then", "than", "too", "very", "just",
    "can", "will", "would", "should", "could", "have", "has", "had", "do", "did",
    "does", "was", "were", "are", "been", "being", "their", "there", "they", "them",
    "his", "her", "she", "he", "him", "our", "your", "its", "come", "open", "monad",
    "please", "thanks", "yes", "okay", "ok", "right", "left", "up", "down",
];

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct VocabEntry {
    pub canonical: String,
    #[serde(default)]
    pub aliases: Vec<String>,
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// Kept for old `vocab.json`; matching no longer gates on this.
    #[serde(default = "default_true")]
    pub fuzzy: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct VocabStore {
    #[serde(default)]
    pub fuzzy_enabled: bool,
    #[serde(default = "default_threshold")]
    pub fuzzy_threshold: f64,
    #[serde(default)]
    pub diag_raw: bool,
    #[serde(default)]
    pub entries: Vec<VocabEntry>,
}

fn default_threshold() -> f64 {
    DEFAULT_THRESHOLD
}

impl Default for VocabStore {
    fn default() -> Self {
        Self {
            fuzzy_enabled: true,
            fuzzy_threshold: DEFAULT_THRESHOLD,
            diag_raw: false,
            entries: Vec::new(),
        }
    }
}

pub fn vocab_path(files_dir: &Path) -> PathBuf {
    files_dir.join(VOCAB_FILE)
}

pub fn load_store(path: &Path) -> VocabStore {
    let Ok(bytes) = std::fs::read(path) else {
        return VocabStore::default();
    };
    serde_json::from_slice(&bytes).unwrap_or_default()
}

pub fn save_store(path: &Path, store: &VocabStore) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_vec_pretty(store)?;
    std::fs::write(path, json)?;
    Ok(())
}

pub fn correct_from_files_dir(files_dir: &Path, text: &str) -> String {
    let store = load_store(&vocab_path(files_dir));
    let out = correct_text(text, &store);
    if store.diag_raw && out != text {
        log::info!("vocab diag raw={:?} corrected={:?}", text, out);
    }
    out
}

pub fn correct_text(text: &str, store: &VocabStore) -> String {
    if text.trim().is_empty() {
        return text.to_string();
    }
    let enabled: Vec<&VocabEntry> = store.entries.iter().filter(|e| e.enabled).collect();
    if enabled.is_empty() {
        return text.to_string();
    }
    let after_alias = apply_aliases(text, &enabled);
    let fuzzy_words: Vec<String> = enabled
        .iter()
        .map(|e| e.canonical.clone())
        .collect();
    apply_fuzzy(&after_alias, &fuzzy_words, store.fuzzy_threshold)
}

fn tokenize(text: &str) -> Vec<String> {
    text.split_whitespace().map(|s| s.to_string()).collect()
}

fn match_key(word: &str) -> String {
    word.chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(|c| c.to_lowercase())
        .collect()
}

fn extract_punct(word: &str) -> (&str, &str) {
    let prefix_end = word
        .char_indices()
        .find(|(_, c)| c.is_alphanumeric())
        .map(|(i, _)| i)
        .unwrap_or(word.len());
    let suffix_start = word
        .char_indices()
        .rev()
        .find(|(_, c)| c.is_alphanumeric())
        .map(|(i, c)| i + c.len_utf8())
        .unwrap_or(0);
    let prefix = if prefix_end > 0 { &word[..prefix_end] } else { "" };
    let suffix = if suffix_start < word.len() {
        &word[suffix_start..]
    } else {
        ""
    };
    (prefix, suffix)
}

fn alias_tokens(alias: &str) -> Vec<String> {
    alias
        .split_whitespace()
        .map(match_key)
        .filter(|s| !s.is_empty())
        .collect()
}

fn entry_aliases(e: &VocabEntry) -> Vec<String> {
    let mut out = e.aliases.clone();
    for a in derived_aliases(&e.canonical) {
        if !out.iter().any(|x| alias_tokens(x) == alias_tokens(&a)) {
            out.push(a);
        }
    }
    out
}

/// Acronym / CamelCase splits so typing `HCOM` catches "h com" / "h come".
fn derived_aliases(canonical: &str) -> Vec<String> {
    let compact = match_key(canonical);
    if compact.chars().count() < 2 {
        return Vec::new();
    }
    let mut out = Vec::new();
    let letters: Vec<char> = compact.chars().collect();
    let alpha_orig: String = canonical.chars().filter(|c| c.is_ascii_alphabetic()).collect();
    let is_acronym = (2..=8).contains(&alpha_orig.len())
        && alpha_orig.chars().all(|c| c.is_ascii_uppercase());

    if is_acronym && letters.iter().all(|c| c.is_ascii_alphabetic()) {
        out.push(letters.iter().map(|c| c.to_string()).collect::<Vec<_>>().join(" "));
        let rest: String = letters[1..].iter().collect();
        out.push(format!("{} {}", letters[0], rest));
        if rest.len() >= 2 && !rest.ends_with('e') {
            out.push(format!("{} {}e", letters[0], rest));
        }
    }

    let camel = split_camel(canonical);
    if camel.len() > 1 {
        let lower: Vec<String> = camel
            .iter()
            .map(|p| match_key(p))
            .filter(|s| !s.is_empty())
            .collect();
        if lower.len() > 1 {
            out.push(lower.join(" "));
        }
        let mut spaced = Vec::new();
        for p in &camel {
            let k = match_key(p);
            let orig_alpha: String = p.chars().filter(|c| c.is_ascii_alphabetic()).collect();
            if orig_alpha.len() >= 2 && orig_alpha.chars().all(|c| c.is_ascii_uppercase()) {
                spaced.extend(k.chars().map(|c| c.to_string()));
            } else if !k.is_empty() {
                spaced.push(k);
            }
        }
        if spaced.len() > lower.len() {
            out.push(spaced.join(" "));
        }
    }
    out
}

fn split_camel(s: &str) -> Vec<String> {
    let mut parts = Vec::new();
    let mut cur = String::new();
    let chs: Vec<char> = s.chars().collect();
    for (i, &c) in chs.iter().enumerate() {
        if c.is_uppercase() && !cur.is_empty() {
            let prev_lower = cur.chars().last().map(|x| x.is_lowercase()).unwrap_or(false);
            let next_lower = chs.get(i + 1).copied().map(|x| x.is_lowercase()).unwrap_or(false);
            if prev_lower || (cur.chars().all(|x| x.is_uppercase()) && next_lower) {
                parts.push(std::mem::take(&mut cur));
            }
        }
        cur.push(c);
    }
    if !cur.is_empty() {
        parts.push(cur);
    }
    parts
}


fn apply_aliases(text: &str, entries: &[&VocabEntry]) -> String {
    let words = tokenize(text);
    if words.is_empty() {
        return text.to_string();
    }
    let mut prepared: Vec<(Vec<String>, &str)> = Vec::new();
    for e in entries {
        for alias in entry_aliases(e) {
            let toks = alias_tokens(&alias);
            if !toks.is_empty() {
                prepared.push((toks, e.canonical.as_str()));
            }
        }
    }
    // Longer aliases first so "open x r" beats "open".
    prepared.sort_by(|a, b| b.0.len().cmp(&a.0.len()));

    let mut i = 0;
    let mut out = Vec::new();
    while i < words.len() {
        let mut hit: Option<(usize, &str)> = None;
        for (toks, canonical) in &prepared {
            let n = toks.len();
            if i + n > words.len() {
                continue;
            }
            let mut ok = true;
            for (k, t) in toks.iter().enumerate() {
                if match_key(&words[i + k]) != *t {
                    ok = false;
                    break;
                }
            }
            if ok {
                hit = Some((n, canonical));
                break;
            }
        }
        if let Some((n, canonical)) = hit {
            let (prefix, _) = extract_punct(&words[i]);
            let (_, suffix) = extract_punct(&words[i + n - 1]);
            out.push(format!("{}{}{}", prefix, canonical, suffix));
            i += n;
        } else {
            out.push(words[i].clone());
            i += 1;
        }
    }
    out.join(" ")
}

fn is_stoplist(key: &str) -> bool {
    STOPLIST.contains(&key)
}

fn levenshtein(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    let mut cur = vec![0; b.len() + 1];
    for (i, ca) in a.iter().enumerate() {
        cur[0] = i + 1;
        for (j, cb) in b.iter().enumerate() {
            let cost = if ca == cb { 0 } else { 1 };
            cur[j + 1] = (prev[j + 1] + 1).min(cur[j] + 1).min(prev[j] + cost);
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[b.len()]
}

fn soundex_code(s: &str) -> Option<[u8; 4]> {
    let mut letters = s.chars().filter(|c| c.is_ascii_alphabetic());
    let first = letters.next()?.to_ascii_uppercase() as u8;
    let mut out = [b'0'; 4];
    out[0] = first;
    let mut last = encode(first);
    let mut n = 1;
    for c in letters {
        let code = encode(c.to_ascii_uppercase() as u8);
        if code == b'0' {
            last = b'0';
            continue;
        }
        if code != last {
            out[n] = code;
            n += 1;
            if n == 4 {
                break;
            }
        }
        last = code;
    }
    Some(out)
}

fn encode(c: u8) -> u8 {
    match c {
        b'B' | b'F' | b'P' | b'V' => b'1',
        b'C' | b'G' | b'J' | b'K' | b'Q' | b'S' | b'X' | b'Z' => b'2',
        b'D' | b'T' => b'3',
        b'L' => b'4',
        b'M' | b'N' => b'5',
        b'R' => b'6',
        _ => b'0',
    }
}

fn find_fuzzy<'a>(
    candidate: &str,
    custom: &'a [String],
    threshold: f64,
) -> Option<(&'a String, f64)> {
    if candidate.is_empty() || candidate.chars().count() > 50 {
        return None;
    }
    if !candidate.chars().all(|c| c.is_ascii_alphanumeric()) {
        return None;
    }
    if candidate.chars().count() < 4 || is_stoplist(candidate) {
        return None;
    }
    let mut best: Option<(&String, f64)> = None;
    for word in custom {
        let key = match_key(word);
        if key.is_empty() || !key.chars().all(|c| c.is_ascii_alphanumeric()) {
            continue;
        }
        if key.chars().count() < 4 || is_stoplist(&key) {
            continue;
        }
        let cand_len = candidate.chars().count();
        let key_len = key.chars().count();
        let len_diff = cand_len.abs_diff(key_len) as f64;
        let max_len = cand_len.max(key_len) as f64;
        let max_allowed = (max_len * 0.25).max(2.0);
        if len_diff > max_allowed {
            continue;
        }
        let dist = levenshtein(candidate, &key);
        let mut score = if max_len > 0.0 {
            dist as f64 / max_len
        } else {
            1.0
        };
        // Soundex only for longer alphabetic tokens — not "come"/"hcom".
        if candidate.chars().count() >= 5
            && key.chars().count() >= 5
            && candidate.chars().all(|c| c.is_ascii_alphabetic())
            && key.chars().all(|c| c.is_ascii_alphabetic())
        {
            if let (Some(a), Some(b)) = (soundex_code(candidate), soundex_code(&key)) {
                if a == b {
                    score *= 0.3;
                }
            }
        }
        if score < threshold && best.as_ref().map_or(true, |(_, s)| score < *s) {
            best = Some((word, score));
        }
    }
    best
}

fn apply_fuzzy(text: &str, custom: &[String], threshold: f64) -> String {
    let words = tokenize(text);
    let mut out = Vec::new();
    let mut i = 0;
    while i < words.len() {
        let mut best: Option<(usize, &String, f64)> = None;
        for n in (1..=3).rev() {
            if i + n > words.len() {
                continue;
            }
            let slice = &words[i..i + n];
            if slice[..n.saturating_sub(1)]
                .iter()
                .any(|w| !extract_punct(w).1.is_empty())
            {
                continue;
            }
            let ngram: String = slice.iter().map(|w| match_key(w)).collect();
            if let Some((repl, score)) = find_fuzzy(&ngram, custom, threshold) {
                if best.as_ref().map_or(true, |(_, _, s)| score < *s) {
                    best = Some((n, repl, score));
                }
            }
        }
        if let Some((n, repl, _)) = best {
            let (prefix, _) = extract_punct(&words[i]);
            let (_, suffix) = extract_punct(&words[i + n - 1]);
            out.push(format!("{}{}{}", prefix, repl, suffix));
            i += n;
        } else {
            out.push(words[i].clone());
            i += 1;
        }
    }
    out.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store(entries: Vec<VocabEntry>, fuzzy: bool) -> VocabStore {
        VocabStore {
            fuzzy_enabled: fuzzy,
            fuzzy_threshold: DEFAULT_THRESHOLD,
            diag_raw: false,
            entries,
        }
    }

    fn entry(canonical: &str, aliases: &[&str], fuzzy: bool) -> VocabEntry {
        VocabEntry {
            canonical: canonical.to_string(),
            aliases: aliases.iter().map(|s| s.to_string()).collect(),
            enabled: true,
            fuzzy,
        }
    }

    #[test]
    fn alias_h_come_to_hcom() {
        let s = store(vec![entry("HCOM", &["h come", "hcom"], false)], false);
        assert_eq!(correct_text("please open h come now", &s), "please open HCOM now");
    }

    #[test]
    fn alias_open_x_r() {
        let s = store(vec![entry("OpenXR", &["open x r", "open xr"], false)], false);
        assert_eq!(correct_text("use open x r today", &s), "use OpenXR today");
    }

    #[test]
    fn alias_nix_o_s() {
        let s = store(vec![entry("NixOS", &["nix o s", "nix os"], false)], false);
        assert_eq!(correct_text("boot nix o s please", &s), "boot NixOS please");
    }

    #[test]
    fn ordinary_come_open_monad_untouched() {
        let s = store(
            vec![
                entry("HCOM", &["h come"], true),
                entry("OpenXR", &["open x r"], true),
                entry("NixOS", &["nix o s"], true),
            ],
            true,
        );
        assert_eq!(correct_text("come open monad", &s), "come open monad");
        assert_eq!(correct_text("please come here", &s), "please come here");
        assert_eq!(correct_text("open the door", &s), "open the door");
    }

    #[test]
    fn punctuation_and_unicode_preserved() {
        let s = store(vec![entry("HCOM", &["h come"], false)], false);
        assert_eq!(correct_text("(h come).", &s), "(HCOM).");
        assert_eq!(correct_text("「hello」", &s), "「hello」");
    }

    #[test]
    fn disabled_entry_is_noop() {
        let mut e = entry("HCOM", &["h come"], false);
        e.enabled = false;
        let s = store(vec![e], false);
        assert_eq!(correct_text("h come", &s), "h come");
    }

    #[test]
    fn word_only_hcom_and_herdr() {
        let s = store(
            vec![entry("HCOM", &[], true), entry("herdr", &[], true)],
            true,
        );
        assert_eq!(correct_text("please open h come now", &s), "please open HCOM now");
        assert_eq!(correct_text("pair with h com", &s), "pair with HCOM");
        assert_eq!(correct_text("restart herder", &s), "restart herdr");
        assert_eq!(correct_text("her keyboard", &s), "her keyboard");
        assert_eq!(correct_text("Charge B", &s), "Charge B");
    }

    #[test]
    fn word_only_openxr() {
        let s = store(vec![entry("OpenXR", &[], true)], true);
        assert_eq!(correct_text("use open x r today", &s), "use OpenXR today");
        assert_eq!(correct_text("open the door", &s), "open the door");
    }

    #[test]
    fn persistence_roundtrip() {
        let dir = std::env::temp_dir().join(format!("adev-vocab-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = vocab_path(&dir);
        let mut s = store(vec![entry("HCOM", &["h come"], false)], false);
        s.diag_raw = true;
        save_store(&path, &s).unwrap();
        let loaded = load_store(&path);
        assert_eq!(loaded.entries.len(), 1);
        assert_eq!(loaded.entries[0].canonical, "HCOM");
        assert!(loaded.diag_raw);
        assert_eq!(
            correct_from_files_dir(&dir, "h come"),
            "HCOM"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn empty_store_passthrough() {
        assert_eq!(correct_text("hello world", &VocabStore::default()), "hello world");
    }
}

use jni::objects::{JClass, JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;

fn default_json() -> String {
    serde_json::to_string_pretty(&VocabStore::default()).unwrap_or_else(|_| "{}".into())
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VocabActivity_loadVocabNative(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
) -> jstring {
    let json = match crate::assets::files_dir(&mut env, &context) {
        Ok(dir) => {
            let path = vocab_path(&dir);
            std::fs::read_to_string(path).unwrap_or_else(|_| default_json())
        }
        Err(_) => default_json(),
    };
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VocabActivity_saveVocabNative(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
    json: JString,
) -> jni::sys::jboolean {
    let Ok(java_str) = env.get_string(&json) else {
        return 0;
    };
    let raw: String = java_str.into();
    let Ok(store) = serde_json::from_str::<VocabStore>(&raw) else {
        return 0;
    };
    let Ok(dir) = crate::assets::files_dir(&mut env, &context) else {
        return 0;
    };
    match save_store(&vocab_path(&dir), &store) {
        Ok(()) => 1,
        Err(_) => 0,
    }
}
