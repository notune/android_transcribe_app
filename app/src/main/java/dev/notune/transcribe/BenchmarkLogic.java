package dev.notune.transcribe;

/**
 * Pure-Java helpers for the speed test.
 *
 * <p>Kept free of Android classes so the decisions can be covered by fast JVM
 * unit tests (see {@code app/src/test}). Formatting and the UI stay in
 * {@link MainActivity}.
 */
final class BenchmarkLogic {

    /** Post-processing is switched off: the test must not touch the network. */
    static final int CLEANING_DISABLED = 0;
    /** Post-processing is on but the endpoint or model is missing. */
    static final int CLEANING_NOT_CONFIGURED = 1;
    /** Post-processing can run: endpoint and model are set. */
    static final int CLEANING_READY = 2;

    private BenchmarkLogic() {
    }

    /**
     * Decides whether the second stage may run. The API key is deliberately
     * not part of the check: local servers often accept no key, and the
     * normal dictation path does not require one either.
     */
    static int cleaningStage(boolean enabled, String endpoint, String model) {
        if (!enabled) {
            return CLEANING_DISABLED;
        }
        if (isBlank(endpoint) || isBlank(model)) {
            return CLEANING_NOT_CONFIGURED;
        }
        return CLEANING_READY;
    }

    /** Audio seconds divided by compute seconds; 0 when compute is unusable. */
    static double realTimeFactor(double audioSecs, double computeSecs) {
        if (!(audioSecs > 0) || !(computeSecs > 0)) {
            return 0;
        }
        return audioSecs / computeSecs;
    }

    /** Recognition plus cleaning; negative inputs are clamped to zero. */
    static double totalSeconds(double computeSecs, double postSecs) {
        double recognition = Math.max(0, computeSecs);
        double cleaning = Math.max(0, postSecs);
        return recognition + cleaning;
    }

    /** True when the model actually produced something to clean or show. */
    static boolean hasTranscript(String text) {
        return !isBlank(text);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
