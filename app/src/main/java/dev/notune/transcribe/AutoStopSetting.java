package dev.notune.transcribe;

final class AutoStopSetting {
    static final float MIN_SECONDS = 1.5f;
    static final float MAX_SECONDS = 8.0f;
    static final float DEFAULT_SECONDS = 3.0f;

    private AutoStopSetting() { }

    static float parse(String value) {
        try {
            float seconds = Float.parseFloat(value == null ? "" : value.trim());
            return seconds >= MIN_SECONDS && seconds <= MAX_SECONDS
                    ? seconds : DEFAULT_SECONDS;
        } catch (RuntimeException e) {
            return DEFAULT_SECONDS;
        }
    }
}
