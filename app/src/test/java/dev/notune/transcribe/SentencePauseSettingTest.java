package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SentencePauseSettingTest {
    @Test public void acceptsOnlyTheReachableSliderRange() {
        assertEquals(1.5f, SentencePauseSetting.parse("1.5"), 0f);
        assertEquals(8.0f, SentencePauseSetting.parse("8.0"), 0f);
        assertEquals(3.0f, SentencePauseSetting.parse("0.5"), 0f);
        assertEquals(3.0f, SentencePauseSetting.parse("60"), 0f);
        assertEquals(3.0f, SentencePauseSetting.parse("broken"), 0f);
        assertEquals(4.0f, SentencePauseSetting.parse("NaN", 4.0f), 0f);
    }
}
