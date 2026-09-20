package dev.notune.transcribe;

final class SentencePauseSetting {
    static final float MIN = 1.5f;
    static final float MAX = 8.0f;

    private SentencePauseSetting() { }

    static float parse(String value) {
        return parse(value, PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS);
    }

    static float parse(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value == null ? "" : value.trim());
            return parsed >= MIN && parsed <= MAX
                    ? parsed : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
