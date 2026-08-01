package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

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
        new File(ctx.getFilesDir(), "bubble_unload_minutes").delete();
        new File(ctx.getFilesDir(), "bubble_size_dp").delete();
    }

    // --- Defaults for new users --------------------------------------------

    @Test
    public void unloadDefaultsToFifteenMinutes() {
        assertEquals(15, BubblePrefs.getUnloadMinutes(ctx));
        assertEquals(15, BubblePrefs.DEFAULT_UNLOAD_MINUTES);
    }

    @Test
    public void sizeDefaultsToMedium() {
        assertEquals(56, BubblePrefs.getSizeDp(ctx));
        assertEquals(56, BubblePrefs.DEFAULT_SIZE_DP);
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

    // --- Size normalization ------------------------------------------------

    @Test
    public void sizeNormalizesToOfferedChoices() {
        assertEquals(48, BubblePrefs.normalizeSizeDp(48));
        assertEquals(56, BubblePrefs.normalizeSizeDp(56));
        assertEquals(72, BubblePrefs.normalizeSizeDp(72));
    }

    @Test
    public void sizeSnapsNearestChoice() {
        assertEquals(48, BubblePrefs.normalizeSizeDp(50));
        assertEquals(56, BubblePrefs.normalizeSizeDp(60));
        assertEquals(72, BubblePrefs.normalizeSizeDp(70));
        assertEquals(72, BubblePrefs.normalizeSizeDp(1000));
    }

    @Test
    public void sizeNeverBelowMinimumTouchTarget() {
        assertEquals(BubblePrefs.MIN_SIZE_DP, BubblePrefs.normalizeSizeDp(0));
        assertEquals(BubblePrefs.MIN_SIZE_DP, BubblePrefs.normalizeSizeDp(-20));
        assertEquals(48, BubblePrefs.MIN_SIZE_DP);
    }

    @Test
    public void sizeSetGetRoundTrip() {
        BubblePrefs.setSizeDp(ctx, 72);
        assertEquals(72, BubblePrefs.getSizeDp(ctx));
        BubblePrefs.setSizeDp(ctx, 48);
        assertEquals(48, BubblePrefs.getSizeDp(ctx));
    }

    @Test
    public void sizeSetNormalizesInvalidValue() {
        BubblePrefs.setSizeDp(ctx, 10);
        assertEquals(48, BubblePrefs.getSizeDp(ctx));
    }

    // --- Position persistence (existing behavior, kept) --------------------

    @Test
    public void positionDefaultsToAbsent() {
        assertEquals(-1, BubblePrefs.getX(ctx));
        assertEquals(-1, BubblePrefs.getY(ctx));
    }

    @Test
    public void positionRoundTrip() {
        BubblePrefs.setX(ctx, 120);
        BubblePrefs.setY(ctx, 340);
        assertEquals(120, BubblePrefs.getX(ctx));
        assertEquals(340, BubblePrefs.getY(ctx));
    }
}
