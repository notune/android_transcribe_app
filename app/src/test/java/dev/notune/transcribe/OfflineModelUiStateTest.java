package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfflineModelUiStateTest {
    @Test
    public void setupStatesRemainDistinctFromRecognitionMessages() {
        OfflineModelUiState state = new OfflineModelUiState();
        assertEquals("Preparing offline model", state.render().headline);
        assertFalse(state.render().retryVisible);

        state.onRecognitionStatus("Listening...");
        assertEquals("Preparing offline model", state.render().headline);

        state.onSetupReady();
        assertEquals("Ready for offline speech", state.render().headline);
        state.onRecognitionStatus("Error: decoder failed");
        assertEquals("Ready for offline speech", state.render().headline);
    }

    @Test
    public void categorizedFailureExposesRetryAndRetryReturnsToPreparing() {
        OfflineModelUiState state = new OfflineModelUiState();
        state.onSetupFailed("lock_timeout", "another process did not finish in time");
        assertEquals("Offline model unavailable", state.render().headline);
        assertTrue(state.render().detail.contains("another process"));
        assertTrue(state.render().retryVisible);

        state.onRetryStarted();
        assertEquals("Preparing offline model", state.render().headline);
        assertFalse(state.render().retryVisible);
    }
}
