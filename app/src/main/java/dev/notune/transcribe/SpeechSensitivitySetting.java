package dev.notune.transcribe;

final class SpeechSensitivitySetting {
    static final float MIN_RATIO = 1.5f;
    static final float MAX_RATIO = 5.0f;
    static final float DEFAULT_RATIO = 3.0f;

    private SpeechSensitivitySetting() { }

    static float parse(String value) {
        try {
            float ratio = Float.parseFloat(value == null ? "" : value.trim());
            return Float.isFinite(ratio) && ratio >= MIN_RATIO && ratio <= MAX_RATIO
                    ? ratio : DEFAULT_RATIO;
        } catch (RuntimeException e) {
            return DEFAULT_RATIO;
        }
    }
}
