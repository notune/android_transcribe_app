package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BubblePrefsTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        for (String f : new String[]{"bubble_unload_minutes", "bubble_size_dp",
                "bubble_disabled", "bubble_side", "bubble_offset_y", "bubble_snooze_until",
                "bubble_hide_search", "bubble_no_keyboard", "a11y_insertion_consent",
                "bubble_style", "bubble_style_light", "bubble_style_dark", "bubble_scale_index", "bubble_opacity",
                "bubble_auto_shrink", "bubble_shrink_dot"}) {
            new File(ctx.getFilesDir(), f).delete();
        }
    }

    // --- Defaults for new users --------------------------------------------

    @Test
    public void unloadDefaultsToFifteenMinutes() {
        assertEquals(15, BubblePrefs.getUnloadMinutes(ctx));
        assertEquals(15, BubblePrefs.DEFAULT_UNLOAD_MINUTES);
    }

    // --- Unload interval normalization -------------------------------------

    @Test
    public void unloadNormalizesToOfferedChoices() {
        assertEquals(0, BubblePrefs.normalizeUnloadMinutes(0));
        assertEquals(5, BubblePrefs.normalizeUnloadMinutes(5));
        assertEquals(15, BubblePrefs.normalizeUnloadMinutes(15));
        assertEquals(30, BubblePrefs.normalizeUnloadMinutes(30));
    }

    @Test
    public void unloadSnapsNearestChoice() {
        assertEquals(0, BubblePrefs.normalizeUnloadMinutes(1));
        assertEquals(5, BubblePrefs.normalizeUnloadMinutes(4));
        assertEquals(15, BubblePrefs.normalizeUnloadMinutes(12));
        assertEquals(30, BubblePrefs.normalizeUnloadMinutes(29));
        assertEquals(30, BubblePrefs.normalizeUnloadMinutes(1000));
    }

    @Test
    public void unloadSetGetRoundTrip() {
        BubblePrefs.setUnloadMinutes(ctx, 30);
        assertEquals(30, BubblePrefs.getUnloadMinutes(ctx));
        BubblePrefs.setUnloadMinutes(ctx, 0);
        assertEquals(0, BubblePrefs.getUnloadMinutes(ctx));
    }

    @Test
    public void unloadSetNormalizesInvalidValue() {
        BubblePrefs.setUnloadMinutes(ctx, 99);
        assertEquals(30, BubblePrefs.getUnloadMinutes(ctx));
    }

    // --- Appearance -------------------------------------------------------------

    @Test
    public void appearanceDefaults() {
        assertEquals(BubbleAppearance.Style.MATERIAL.ordinal(), BubblePrefs.getStyle(ctx));
        assertEquals(2, BubblePrefs.getScaleIndex(ctx));
        assertEquals(100, BubblePrefs.getOpacity(ctx));
        assertFalse(BubblePrefs.isAutoShrink(ctx));
        assertFalse(BubblePrefs.isShrinkDot(ctx));
        assertEquals(56, BubblePrefs.load(ctx).sizeDp);
    }

    @Test
    public void styleRoundTrip() {
        BubblePrefs.setStyle(ctx, BubbleAppearance.Style.TINTED_BLUR);
        assertEquals(BubbleAppearance.Style.TINTED_BLUR.ordinal(), BubblePrefs.getStyle(ctx));
        assertEquals(BubbleAppearance.Style.TINTED_BLUR.ordinal(), BubblePrefs.load(ctx).style);
    }

    @Test
    public void perThemeStyleMigratesToOneStyle() throws Exception {
        // Old ordinals: 0 MATERIAL, 1 MONO, 2 GLASS (removed -> BLUR), 3 BLUR.
        int[][] cases = {{0, 0}, {1, 1}, {2, 2}, {3, 2}};
        for (int[] c : cases) {
            new File(ctx.getFilesDir(), "bubble_style").delete();
            java.nio.file.Files.write(new File(ctx.getFilesDir(), "bubble_style_light").toPath(),
                    String.valueOf(c[0]).getBytes());
            java.nio.file.Files.write(new File(ctx.getFilesDir(), "bubble_style_dark").toPath(),
                    "1".getBytes());
            assertEquals(c[1], BubblePrefs.getStyle(ctx));
            assertFalse(new File(ctx.getFilesDir(), "bubble_style_light").exists());
            assertFalse(new File(ctx.getFilesDir(), "bubble_style_dark").exists());
        }
    }

    @Test
    public void scaleIndexRoundTripAndNormalization() {
        BubblePrefs.setScaleIndex(ctx, 0);
        assertEquals(0, BubblePrefs.getScaleIndex(ctx));
        assertEquals(48, BubblePrefs.load(ctx).sizeDp); // 39 dp disc, 48 dp touch target
        BubblePrefs.setScaleIndex(ctx, 9);
        assertEquals(2, BubblePrefs.getScaleIndex(ctx));
    }

    @Test
    public void legacySizeDpMigrates() throws Exception {
        int[][] cases = {{48, 1}, {56, 2}, {72, 3}};
        for (int[] c : cases) {
            new File(ctx.getFilesDir(), "bubble_scale_index").delete();
            java.nio.file.Files.write(new File(ctx.getFilesDir(), "bubble_size_dp").toPath(),
                    String.valueOf(c[0]).getBytes());
            assertEquals(c[1], BubblePrefs.getScaleIndex(ctx));
            assertFalse(new File(ctx.getFilesDir(), "bubble_size_dp").exists());
        }
    }

    @Test
    public void opacitySnapsToSteps() {
        BubblePrefs.setOpacity(ctx, 64);
        assertEquals(60, BubblePrefs.getOpacity(ctx));
        BubblePrefs.setOpacity(ctx, 5);
        assertEquals(20, BubblePrefs.getOpacity(ctx));
        BubblePrefs.setOpacity(ctx, 150);
        assertEquals(100, BubblePrefs.getOpacity(ctx));
    }

    @Test
    public void shrinkFlagsRoundTrip() {
        BubblePrefs.setAutoShrink(ctx, true);
        BubblePrefs.setShrinkDot(ctx, true);
        BubbleHost.Settings s = BubblePrefs.load(ctx);
        assertTrue(s.autoShrink);
        assertTrue(s.shrinkDot);
    }

    // --- Flow bubble settings ------------------------------------------------

    @Test
    public void flowDefaults() {
        assertTrue(BubblePrefs.isEnabled(ctx));
        assertFalse(BubblePrefs.isHideInSearch(ctx));
        assertEquals(BubbleController.SIDE_RIGHT, BubblePrefs.getSide(ctx));
        assertEquals(0, BubblePrefs.getOffsetY(ctx));
        assertEquals(0L, BubblePrefs.getSnoozeUntil(ctx));
    }

    @Test
    public void enabledRoundTrip() {
        BubblePrefs.setEnabled(ctx, false);
        assertFalse(BubblePrefs.isEnabled(ctx));
        BubblePrefs.setEnabled(ctx, true);
        assertTrue(BubblePrefs.isEnabled(ctx));
    }

    @Test
    public void hideInSearchRoundTrip() {
        BubblePrefs.setHideInSearch(ctx, true);
        assertTrue(BubblePrefs.isHideInSearch(ctx));
        BubblePrefs.setHideInSearch(ctx, false);
        assertFalse(BubblePrefs.isHideInSearch(ctx));
    }

    @Test
    public void showWithoutKeyboardDefaultsOffAndRoundTrips() {
        assertFalse(BubblePrefs.isShowWithoutKeyboard(ctx));
        BubblePrefs.setShowWithoutKeyboard(ctx, true);
        assertTrue(BubblePrefs.isShowWithoutKeyboard(ctx));
        assertTrue(BubblePrefs.load(ctx).showWithoutKeyboard);
    }

    @Test
    public void sideRoundTripAndNormalization() {
        BubblePrefs.setSide(ctx, BubbleController.SIDE_LEFT);
        assertEquals(BubbleController.SIDE_LEFT, BubblePrefs.getSide(ctx));
        BubblePrefs.setSide(ctx, 7);
        assertEquals(BubbleController.SIDE_RIGHT, BubblePrefs.getSide(ctx));
    }

    @Test
    public void offsetNeverNegative() {
        BubblePrefs.setOffsetY(ctx, 240);
        assertEquals(240, BubblePrefs.getOffsetY(ctx));
        BubblePrefs.setOffsetY(ctx, -50);
        assertEquals(0, BubblePrefs.getOffsetY(ctx));
    }

    @Test
    public void snoozeRoundTripAndClear() {
        long until = 1_900_000_000_000L;
        BubblePrefs.setSnoozeUntil(ctx, until);
        assertEquals(until, BubblePrefs.getSnoozeUntil(ctx));
        BubblePrefs.setSnoozeUntil(ctx, 0);
        assertEquals(0L, BubblePrefs.getSnoozeUntil(ctx));
    }

    @Test
    public void loadSnapshotsAllSettings() {
        BubblePrefs.setEnabled(ctx, false);
        BubblePrefs.setA11yConsent(ctx, true);
        BubblePrefs.setSide(ctx, BubbleController.SIDE_LEFT);
        BubblePrefs.setOffsetY(ctx, 30);
        BubbleHost.Settings s = BubblePrefs.load(ctx);
        assertFalse(s.enabled);
        assertTrue(s.consent);
        assertEquals(BubbleController.SIDE_LEFT, s.side);
        assertEquals(30, s.offsetY);
        assertEquals(15, s.unloadMinutes);
        assertEquals(56, s.sizeDp);
    }
}
