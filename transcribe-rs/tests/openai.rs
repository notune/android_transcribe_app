use std::path::PathBuf;

use transcribe_rs::{
    remote::openai::{self, OpenAIRequestParams},
    RemoteTranscriptionEngine,
};

#[tokio::test]
async fn test_dots_transcription() {
    let engine = openai::default_engine();

    // Load the JFK audio file
    let audio_path = PathBuf::from("samples/dots.wav");

    // Transcribe with temperature 0
    let result = engine
        .transcribe_file(
            &audio_path,
            OpenAIRequestParams::builder()
                .temperature(0.0)
                .build()
                .expect("Default parameters shoul be valid"),
        )
        .await
        .expect("Failed to transcribe");

    let expected = "Of course, it was impossible to connect the dots looking forward when I was in college, but it was very, very clear looking backwards ten years later. Again, you can't connect the dots looking forward, you can only connect them looking backwards. So you have to trust that the dots will somehow connect in your future. You have to trust in something, your gut, destiny, life, karma, whatever. Because believing that the dots will connect down the road will give you the confidence to follow your heart even when it leads you off the well-worn path, and that will make all the difference.";
    assert_eq!(
        result.text.trim(),
        expected,
        "\nExpected: '{}'\nActual: '{}'",
        expected,
        result.text.trim()
    );
}
