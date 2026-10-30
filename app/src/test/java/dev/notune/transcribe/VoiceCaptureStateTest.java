package dev.notune.transcribe;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceCaptureStateTest {
    @Test public void fallbackWarningDoesNotCancelCaptureOrChangeGeneration() {
        VoiceCaptureState s = new VoiceCaptureState();
        assertTrue(s.begin());
        long id = s.generation();
        s.status("Error loading foo.gguf — using built-in model");
        assertTrue(s.isRecording());
        assertFalse(s.isFinishing());
        assertEquals(id, s.generation());
        assertTrue(s.stop());
        assertTrue(s.complete(id));
    }
    @Test public void readyAndWaitingAreDisplayOnly() {
        VoiceCaptureState s = new VoiceCaptureState();
        s.begin();
        s.status("Waiting for model...");
        assertTrue(s.isRecording());
        s.stop();
        s.status("Ready");
        assertTrue(s.isFinishing());
    }
    @Test public void startAndStopAreIdempotent() {
        VoiceCaptureState s = new VoiceCaptureState();
        assertTrue(s.begin());
        long id = s.generation();
        assertFalse(s.begin());
        assertEquals(id, s.generation());
        assertTrue(s.stop());
        assertFalse(s.stop());
        assertFalse(s.begin());
        assertTrue(s.complete(id));
        assertFalse(s.complete(id));
        assertTrue(s.begin());
    }
    @Test public void terminalFailureIsExplicitAndReenablesNextCapture() {
        VoiceCaptureState s = new VoiceCaptureState();
        s.begin(); long id = s.generation();
        assertTrue(s.fail(id));
        assertFalse(s.isRecording()); assertFalse(s.isFinishing());
        assertFalse(s.fail(id)); assertFalse(s.complete(id));
        assertTrue(s.begin());
    }
    @Test public void lateResultsCannotCompleteANewerCapture() {
        VoiceCaptureState s = new VoiceCaptureState();
        s.begin(); long old = s.generation();
        s.cancel(); s.begin(); s.stop();
        assertFalse(s.complete(old)); assertFalse(s.fail(old));
        assertTrue(s.isFinishing());
        assertTrue(s.complete(s.generation()));
    }
    @Test public void finishingFailureDoesNotReturnSuccess() {
        VoiceCaptureState s = new VoiceCaptureState();
        s.begin(); long id = s.generation(); s.stop();
        assertTrue(s.fail(id)); assertFalse(s.complete(id));
    }
    @Test public void repeatedRecordingsCanRunWithoutRecreatingState() {
        VoiceCaptureState s = new VoiceCaptureState();
        for (int i = 0; i < 10; i++) {
            assertTrue(s.begin()); long id = s.generation();
            assertTrue(s.stop()); assertTrue(s.complete(id));
        }
    }
}
