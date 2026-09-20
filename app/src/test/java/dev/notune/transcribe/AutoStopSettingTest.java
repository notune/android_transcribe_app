package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoStopSettingTest {
    @Test public void acceptsOnlyTheSliderRange() {
        assertEquals(1.5f, AutoStopSetting.parse("1.5"), 0f);
        assertEquals(8.0f, AutoStopSetting.parse(" 8.0\n"), 0f);
        assertEquals(3.0f, AutoStopSetting.parse("1.49"), 0f);
        assertEquals(3.0f, AutoStopSetting.parse("8.01"), 0f);
        assertEquals(3.0f, AutoStopSetting.parse("NaN"), 0f);
        assertEquals(3.0f, AutoStopSetting.parse("broken"), 0f);
    }
}
