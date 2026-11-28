# The Rust code (src/*.rs) calls these methods back by name over JNI, which
# R8 can't see: they must be neither removed, renamed nor changed in
# signature. (Native methods and their classes are kept by the default rules.)
# Match on the name only: a changed argument list must not silently drop a
# callback the native layer still calls.
-keepclassmembers class dev.notune.transcribe.** {
    void onStatusUpdate(...);
    void onAudioLevel(...);
    void onAutoStop(...);
    void onTextTranscribed(...);
    void onTranscriptionFinished(...);
    void onPartialText(...);
    void onBenchmarkResult(...);
    void onSubtitleText(...);
    void onReadyForSpeech(...);
    void onBeginningOfSpeech(...);
    void onEndOfSpeech(...);
    void onRmsChanged(...);
    void onResults(...);
    void onError(...);
}

# Shrink and optimize, but keep class and method names, so stack traces in
# bug reports stay readable (obfuscating would save only about 50 KB). Exact
# line numbers need the build's mapping.txt; building the release tag again
# reproduces it.
-dontobfuscate
