package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.notune.transcribe.BubbleAppearance.Shrink;
import dev.notune.transcribe.BubbleAppearance.Style;
import dev.notune.transcribe.BubbleController.Mode;

public class BubbleAppearanceTest {

    static BubbleHost.Settings settings(Style style, int scaleIndex, int opacity,
                                        boolean autoShrink, boolean dot) {
        return new BubbleHost.Settings(true, true, false, false, 15,
                BubbleController.SIDE_RIGHT, 0, 0, style.ordinal(),
                scaleIndex, opacity, autoShrink, dot);
    }

    @Test
    public void oneStyleForLightAndDark() {
        BubbleHost.Settings s = settings(Style.MONO, 2, 100, false, false);
        assertEquals(Style.MONO, BubbleAppearance.resolve(s, false, true, Mode.IDLE, false).style);
        assertEquals(Style.MONO, BubbleAppearance.resolve(s, true, true, Mode.IDLE, false).style);
    }

    @Test
    public void opacityOnlyWhenIdle() {
        BubbleHost.Settings s = settings(Style.MATERIAL, 2, 40, false, false);
        assertEquals(0.4f, BubbleAppearance.resolve(s, false, true, Mode.IDLE, false).alpha, 1e-6);
        assertEquals(1f, BubbleAppearance.resolve(s, false, true, Mode.RECORDING_TAP, false).alpha, 1e-6);
        assertEquals(1f, BubbleAppearance.resolve(s, false, true, Mode.RECORDING_HOLD, false).alpha, 1e-6);
        assertEquals(1f, BubbleAppearance.resolve(s, false, true, Mode.PROCESSING, false).alpha, 1e-6);
    }

    @Test
    public void shrinkOnlyWhenIdleAndEnabled() {
        BubbleHost.Settings small = settings(Style.MATERIAL, 2, 100, true, false);
        BubbleHost.Settings dot = settings(Style.MATERIAL, 2, 100, true, true);
        BubbleHost.Settings off = settings(Style.MATERIAL, 2, 100, false, true);
        assertEquals(Shrink.SMALL, BubbleAppearance.resolve(small, false, true, Mode.IDLE, true).shrink);
        assertEquals(Shrink.DOT, BubbleAppearance.resolve(dot, false, true, Mode.IDLE, true).shrink);
        assertEquals(Shrink.NONE, BubbleAppearance.resolve(off, false, true, Mode.IDLE, true).shrink);
        assertEquals(Shrink.NONE, BubbleAppearance.resolve(small, false, true, Mode.IDLE, false).shrink);
        assertEquals(Shrink.NONE, BubbleAppearance.resolve(small, false, true, Mode.RECORDING_TAP, true).shrink);
        assertEquals(Shrink.NONE, BubbleAppearance.resolve(small, false, true, Mode.PROCESSING, true).shrink);
    }

    @Test
    public void blurOnlyForBlurStyleWhenSupported() {
        for (Style style : new Style[]{Style.BLUR, Style.TINTED_BLUR}) {
            BubbleHost.Settings s = settings(style, 2, 100, false, false);
            assertTrue(BubbleAppearance.resolve(s, false, true, Mode.IDLE, false).blur);
            assertTrue(BubbleAppearance.resolve(s, true, true, Mode.RECORDING_TAP, false).blur);
            assertFalse(BubbleAppearance.resolve(s, false, false, Mode.IDLE, false).blur);
        }
        for (Style style : new Style[]{Style.MATERIAL, Style.MONO}) {
            assertFalse(BubbleAppearance.resolve(settings(style, 2, 100, false, false),
                    false, true, Mode.IDLE, false).blur);
        }
    }

    @Test
    public void sizesPerScaleIndex() {
        int[] disc = {39, 48, 56, 64};
        int[] window = {48, 48, 56, 64};
        for (int i = 0; i < 4; i++) {
            BubbleAppearance a = BubbleAppearance.resolve(
                    settings(Style.MATERIAL, i, 100, false, false),
                    false, true, Mode.IDLE, false);
            assertEquals(disc[i], a.discDp());
            assertEquals(window[i], a.currentWindowDp());
            assertEquals(window[i], BubbleAppearance.windowDpForIndex(i));
        }
    }

    @Test
    public void shrunkSizes() {
        BubbleAppearance small = BubbleAppearance.resolve(
                settings(Style.MATERIAL, 2, 100, true, false), false, true, Mode.IDLE, true);
        assertEquals(34, small.currentDiscDp()); // 56 * 0.6
        assertEquals(40, small.currentWindowDp());
        BubbleAppearance dot = BubbleAppearance.resolve(
                settings(Style.MATERIAL, 2, 100, true, true), false, true, Mode.IDLE, true);
        assertEquals(12, dot.currentDiscDp());
        assertEquals(40, dot.currentWindowDp());
    }

    @Test
    public void invalidValuesFallBack() {
        assertEquals(Style.MATERIAL, BubbleAppearance.styleOf(7));
        assertEquals(1.0f, BubbleAppearance.scaleOf(-1), 1e-6);
        assertEquals(20, BubbleAppearance.normalizeOpacity(0));
        assertEquals(100, BubbleAppearance.normalizeOpacity(250));
        assertEquals(70, BubbleAppearance.normalizeOpacity(66));
    }
}
