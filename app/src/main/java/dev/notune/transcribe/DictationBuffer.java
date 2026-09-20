package dev.notune.transcribe;

/** Ordered whole-result buffer shared by the IME and recognition popup. */
final class DictationBuffer {
    private long sessionId;
    private long nextSequence;
    private PieceJoiner joiner = new PieceJoiner();
    private final StringBuilder text = new StringBuilder();

    synchronized void reset(long newSessionId) {
        sessionId = newSessionId;
        nextSequence = 0;
        joiner = new PieceJoiner();
        text.setLength(0);
    }

    synchronized boolean accept(long callbackSessionId, long sequence, String piece,
                                float pauseBeforeSeconds, float sentencePauseSeconds) {
        if (callbackSessionId != sessionId || piece == null) {
            return false;
        }
        if (sequence < nextSequence) return true;
        if (sequence != nextSequence) return false;
        if (!piece.trim().isEmpty()) {
            text.append(joiner.join(piece, pauseBeforeSeconds, sentencePauseSeconds));
        }
        nextSequence++;
        return true;
    }

    synchronized String finish(long callbackSessionId) {
        if (callbackSessionId != sessionId) return null;
        text.append(joiner.finish());
        return text.toString().trim();
    }

    synchronized void discard() {
        joiner.finish();
        text.setLength(0);
    }
}
