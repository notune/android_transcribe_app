package dev.notune.transcribe;

/** Keeps offline-model setup presentation separate from recognition progress. */
final class OfflineModelUiState {
    static final class Rendered {
        final String headline;
        final String detail;
        final boolean retryVisible;

        Rendered(String headline, String detail, boolean retryVisible) {
            this.headline = headline;
            this.detail = detail;
            this.retryVisible = retryVisible;
        }
    }

    private Rendered rendered = new Rendered("Preparing offline model", "", false);

    Rendered render() {
        return rendered;
    }

    void onSetupReady() {
        rendered = new Rendered("Ready for offline speech", "", false);
    }

    void onSetupFailed(String category, String detail) {
        String cause = detail == null || detail.trim().isEmpty() ? category : detail;
        rendered = new Rendered("Offline model unavailable", cause, true);
    }

    void onRetryStarted() {
        rendered = new Rendered("Preparing offline model", "", false);
    }

    void onRecognitionStatus(String ignored) {
        // Recognition is deliberately rendered elsewhere and cannot overwrite setup.
    }
}
