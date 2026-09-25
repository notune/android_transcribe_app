package dev.notune.transcribe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

import dev.notune.transcribe.BubbleController.Mode;

/**
 * Turns a {@link BubbleAppearance} into drawables and colours. The service and
 * the style-screen preview both use it, so they always look the same. Every
 * colour comes from the Material theme (Material You) or from resources.
 */
final class BubbleStyler {
    private static final int RING_DP = 3;
    /** Tint strength over a real blur, and without blur (frosted fallback). */
    private static final float TINT_OVER_BLUR = 0.55f;
    private static final float TINT_FROSTED = 0.9f;

    private final Context base;
    private Context lightTheme;
    private Context darkTheme;

    BubbleStyler(Context base) {
        this.base = base;
    }

    /** Themed contexts are cached; call after a wallpaper/theme change. */
    void invalidate() {
        lightTheme = null;
        darkTheme = null;
    }

    private Context themed(boolean night) {
        if (night) {
            if (darkTheme == null) darkTheme = buildTheme(AppCompatDelegate.MODE_NIGHT_YES);
            return darkTheme;
        }
        if (lightTheme == null) lightTheme = buildTheme(AppCompatDelegate.MODE_NIGHT_NO);
        return lightTheme;
    }

    private Context buildTheme(int nightMode) {
        Context night = ThemePrefs.wrapForNight(base, nightMode);
        return DynamicColors.wrapContextIfAvailable(new ContextThemeWrapper(night, R.style.AppTheme));
    }

    private int attr(boolean night, int attrRes) {
        return MaterialColors.getColor(themed(night), attrRes, 0xFF808080);
    }

    private int res(int colorRes) {
        return ContextCompat.getColor(base, colorRes);
    }

    private float dp(float v) {
        return v * base.getResources().getDisplayMetrics().density;
    }

    // --- Bubble ----------------------------------------------------------------------

    /**
     * Paints the bubble disc.
     *
     * @param viewBlur the host blurs behind the disc itself (Samsung view blur):
     *                 the disc then gets no fill, and the returned colour is the
     *                 tint the host must pass to the blur
     * @return the disc's fill colour
     */
    int applyBubble(View disc, ImageView icon, ProgressBar progress,
                    BubbleAppearance a, Mode mode, boolean night, boolean viewBlur) {
        boolean recording = mode == Mode.RECORDING_TAP || mode == Mode.RECORDING_HOLD;
        boolean processing = mode == Mode.PROCESSING;
        int err = attr(night, com.google.android.material.R.attr.colorError);
        int fill, iconColor, ring = 0;

        switch (a.style) {
            case MONO:
                fill = res(night ? R.color.bubble_mono_light_fill : R.color.bubble_mono_dark_fill);
                iconColor = res(night ? R.color.bubble_mono_light_icon : R.color.bubble_mono_dark_icon);
                if (processing) {
                    fill = res(night ? R.color.bubble_mono_light_processing
                            : R.color.bubble_mono_dark_processing);
                }
                if (recording) ring = iconColor;
                break;
            case BLUR:
                fill = res(a.blur
                        ? (night ? R.color.bubble_blur_tint_dark : R.color.bubble_blur_tint_light)
                        : (night ? R.color.bubble_frost_dark : R.color.bubble_frost_light));
                iconColor = recording ? err
                        : attr(night, com.google.android.material.R.attr.colorOnSurface);
                ring = recording ? err
                        : res(night ? R.color.bubble_blur_edge_dark : R.color.bubble_blur_edge_light);
                break;
            case TINTED_BLUR: {
                int container, onContainer;
                if (recording) {
                    container = com.google.android.material.R.attr.colorErrorContainer;
                    onContainer = com.google.android.material.R.attr.colorOnErrorContainer;
                    ring = err;
                } else if (processing) {
                    container = com.google.android.material.R.attr.colorTertiaryContainer;
                    onContainer = com.google.android.material.R.attr.colorOnTertiaryContainer;
                } else {
                    container = com.google.android.material.R.attr.colorPrimaryContainer;
                    onContainer = com.google.android.material.R.attr.colorOnPrimaryContainer;
                }
                fill = withAlpha(attr(night, container), a.blur ? TINT_OVER_BLUR : TINT_FROSTED);
                iconColor = attr(night, onContainer);
                if (ring == 0) {
                    ring = res(night ? R.color.bubble_blur_edge_dark : R.color.bubble_blur_edge_light);
                }
                break;
            }
            default: // MATERIAL
                if (recording) {
                    fill = err;
                    iconColor = attr(night, com.google.android.material.R.attr.colorOnError);
                    ring = attr(night, com.google.android.material.R.attr.colorErrorContainer);
                } else if (processing) {
                    fill = attr(night, com.google.android.material.R.attr.colorTertiary);
                    iconColor = attr(night, com.google.android.material.R.attr.colorOnTertiary);
                } else {
                    fill = attr(night, com.google.android.material.R.attr.colorPrimary);
                    iconColor = attr(night, com.google.android.material.R.attr.colorOnPrimary);
                }
                break;
        }

        boolean thinRing = ring != 0 && !recording && BubbleAppearance.isBlurStyle(a.style);
        float ringWidth = ring == 0 ? 0 : dp(thinRing ? 1 : RING_DP);
        boolean blurFillsDisc = viewBlur && a.blur;
        disc.setBackground(oval(blurFillsDisc ? Color.TRANSPARENT : fill, ring, ringWidth));
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        progress.setIndeterminateTintList(ColorStateList.valueOf(iconColor));
        // A dot is too small for an icon.
        icon.setAlpha(a.shrink == BubbleAppearance.Shrink.DOT ? 0f : 1f);
        disc.setAlpha(a.alpha);
        return fill;
    }

    // --- Cancel chip and snooze target --------------------------------------------------

    void applyChip(View chip, ImageView icon, BubbleAppearance a, boolean night, boolean armed) {
        applySmall(chip, icon, a, night, armed, false);
    }

    void applyTarget(View target, ImageView icon, BubbleAppearance a, boolean night, boolean armed) {
        applySmall(target, icon, a, night, armed, true);
    }

    /** The chip and target have no blur of their own; Blur uses the frosted look. */
    private void applySmall(View view, ImageView icon, BubbleAppearance a, boolean night,
                            boolean armed, boolean target) {
        int fill, iconColor, stroke = 0;
        switch (a.style) {
            case MONO: {
                int f = res(night ? R.color.bubble_mono_light_fill : R.color.bubble_mono_dark_fill);
                int ic = res(night ? R.color.bubble_mono_light_icon : R.color.bubble_mono_dark_icon);
                // Armed inverts, so B&W stays free of colour.
                fill = armed ? ic : f;
                iconColor = armed ? f : ic;
                stroke = armed ? f : 0;
                break;
            }
            case BLUR: {
                int accent = attr(night, target
                        ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorError);
                fill = res(night ? R.color.bubble_frost_dark : R.color.bubble_frost_light);
                stroke = armed ? accent
                        : res(night ? R.color.bubble_blur_edge_dark : R.color.bubble_blur_edge_light);
                iconColor = armed ? accent : attr(night, com.google.android.material.R.attr.colorOnSurface);
                break;
            }
            default: { // MATERIAL, TINTED_BLUR
                if (armed) {
                    fill = attr(night, target ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorError);
                    iconColor = attr(night, target ? com.google.android.material.R.attr.colorOnPrimary
                            : com.google.android.material.R.attr.colorOnError);
                } else {
                    fill = attr(night, com.google.android.material.R.attr.colorSurfaceContainerHighest);
                    iconColor = attr(night, com.google.android.material.R.attr.colorOnSurface);
                }
                break;
            }
        }
        view.setBackground(oval(fill, stroke, dp(stroke == 0 ? 0 : (armed ? RING_DP : 1))));
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
    }

    // --- Drawables ------------------------------------------------------------------------

    private static int withAlpha(int color, float alpha) {
        return ColorUtils.setAlphaComponent(color, Math.round(255 * alpha));
    }

    private static Drawable oval(int fill, int stroke, float strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (stroke != 0 && strokeWidth > 0) d.setStroke(Math.round(strokeWidth), stroke);
        return d;
    }
}
