# The Rust code (src/*.rs) calls these methods back by name over JNI, which
# R8 can't see: they must be neither removed, renamed nor changed in
# signature. (Native methods and their classes are kept by the default rules.)
-keepclassmembers class dev.notune.transcribe.** {
    void onStatusUpdate(java.lang.String);
    void onAudioLevel(float);
    void onAutoStop();
    void onTextTranscribed(java.lang.String);
    void onPartialText(java.lang.String);
    void onStreamText(java.lang.String, java.lang.String);
    void onBenchmarkResult(float, float, java.lang.String);
    void onSubtitleText(java.lang.String, boolean);
    void onReadyForSpeech();
    void onBeginningOfSpeech();
    void onEndOfSpeech();
    void onRmsChanged(float);
    void onResults(java.lang.String);
    void onError(int);
}

# Shrink and optimize, but keep class and method names, so stack traces in
# bug reports stay readable (obfuscating would save only about 50 KB). Exact
# line numbers need the build's mapping.txt; building the release tag again
# reproduces it.
-dontobfuscate
