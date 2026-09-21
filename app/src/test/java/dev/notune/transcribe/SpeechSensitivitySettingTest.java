package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpeechSensitivitySettingTest {
    @Test public void acceptsOnlyTheDisplayedRange() {
        assertEquals(1.5f, SpeechSensitivitySetting.parse("1.5"), 0f);
        assertEquals(5.0f, SpeechSensitivitySetting.parse(" 5.0\n"), 0f);
        assertEquals(3.0f, SpeechSensitivitySetting.parse("1.49"), 0f);
        assertEquals(3.0f, SpeechSensitivitySetting.parse("5.01"), 0f);
        assertEquals(3.0f, SpeechSensitivitySetting.parse("NaN"), 0f);
        assertEquals(3.0f, SpeechSensitivitySetting.parse("broken"), 0f);
    }
}
