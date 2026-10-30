package dev.notune.transcribe;

/** UI-thread capture lifecycle. Display strings never determine ownership. */
final class VoiceCaptureState {
    private boolean recording;
    private boolean finishing;
    private long generation;
    private String status = "Initializing...";

    boolean begin() {
        if (recording || finishing) return false;
        ++generation;
        recording = true;
        return true;
    }
    boolean stop() {
        if (!recording || finishing) return false;
        recording = false;
        finishing = true;
        return true;
    }
    void cancel() {
        ++generation;
        recording = false;
        finishing = false;
    }
    boolean complete(long id) {
        if (!accepts(id) || !finishing) return false;
        finishing = false;
        return true;
    }
    boolean fail(long id) {
        if (!accepts(id) || (!recording && !finishing)) return false;
        cancel();
        return true;
    }
    boolean accepts(long id) { return generation == id; }
    boolean isRecording() { return recording; }
    boolean isFinishing() { return finishing; }
    long generation() { return generation; }
    void status(String text) { if (text != null) status = text; }
    String status() { return status; }
}
