package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BenchmarkLogicTest {

    @Test
    public void disabledCleaningWinsOverConfiguredFields() {
        assertEquals(BenchmarkLogic.CLEANING_DISABLED,
                BenchmarkLogic.cleaningStage(false, "https://api.example.com", "model"));
    }

    @Test
    public void missingEndpointOrModelMeansNotConfigured() {
        assertEquals(BenchmarkLogic.CLEANING_NOT_CONFIGURED,
                BenchmarkLogic.cleaningStage(true, "", "model"));
        assertEquals(BenchmarkLogic.CLEANING_NOT_CONFIGURED,
                BenchmarkLogic.cleaningStage(true, "https://api.example.com", ""));
        assertEquals(BenchmarkLogic.CLEANING_NOT_CONFIGURED,
                BenchmarkLogic.cleaningStage(true, "   ", "model"));
        assertEquals(BenchmarkLogic.CLEANING_NOT_CONFIGURED,
                BenchmarkLogic.cleaningStage(true, null, null));
    }

    @Test
    public void configuredCleaningIsReady() {
        assertEquals(BenchmarkLogic.CLEANING_READY,
                BenchmarkLogic.cleaningStage(true, "https://api.example.com", "model"));
    }

    @Test
    public void realTimeFactorDividesAudioByCompute() {
        assertEquals(8.0, BenchmarkLogic.realTimeFactor(10.0, 1.25), 0.0001);
        assertEquals(1.0, BenchmarkLogic.realTimeFactor(3.0, 3.0), 0.0001);
    }

    @Test
    public void realTimeFactorIsZeroWhenComputeIsUnusable() {
        assertEquals(0.0, BenchmarkLogic.realTimeFactor(10.0, 0.0), 0.0001);
        assertEquals(0.0, BenchmarkLogic.realTimeFactor(10.0, -1.0), 0.0001);
        assertEquals(0.0, BenchmarkLogic.realTimeFactor(0.0, 1.0), 0.0001);
    }

    @Test
    public void totalAddsRecognitionAndCleaning() {
        assertEquals(1.9, BenchmarkLogic.totalSeconds(1.2, 0.7), 0.0001);
        assertEquals(1.2, BenchmarkLogic.totalSeconds(1.2, 0.0), 0.0001);
    }

    @Test
    public void totalClampsNegativeParts() {
        assertEquals(1.2, BenchmarkLogic.totalSeconds(1.2, -0.7), 0.0001);
        assertEquals(0.7, BenchmarkLogic.totalSeconds(-1.2, 0.7), 0.0001);
    }

    @Test
    public void transcriptMustContainVisibleText() {
        assertTrue(BenchmarkLogic.hasTranscript("Привет"));
        assertTrue(BenchmarkLogic.hasTranscript("  Привет  "));
        assertFalse(BenchmarkLogic.hasTranscript(""));
        assertFalse(BenchmarkLogic.hasTranscript("   "));
        assertFalse(BenchmarkLogic.hasTranscript(null));
    }
}
