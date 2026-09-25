package dev.notune.transcribe;

import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.app.Activity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;

import dev.notune.transcribe.BubbleAppearance.Style;
import dev.notune.transcribe.BubbleController.Mode;

/**
 * The Flow bubble part of the Style tab: pick the style, the size, the idle
 * opacity and auto-shrink, with a live preview of the bubble's idle,
 * recording and processing states on light and dark sample backgrounds.
 * Light or dark follows the app's Appearance setting (same tab).
 */
final class StylePage {
    private static final int PREVIEW_GAP_DP = 8;
    private static final int PREVIEW_BLUR_DP = 12;
    private static final Mode[] PREVIEW_MODES = {Mode.IDLE, Mode.RECORDING_TAP, Mode.PROCESSING};
    private static final int[] STYLE_LABELS = {
            R.string.style_material, R.string.style_mono, R.string.style_blur, R.string.style_tinted_blur};
    private static final int[] SIZE_LABELS = {
            R.string.style_size_xs, R.string.style_size_s, R.string.style_size_m, R.string.style_size_l};

    private final Activity activity;
    private final View root;
    private BubbleStyler mStyler;
    private boolean mBlurSupported;

    StylePage(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        mStyler = new BubbleStyler(activity);
        mBlurSupported = BlurSupport.any((WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE));
        root.findViewById(R.id.text_blur_unsupported).setVisibility(mBlurSupported ? View.GONE : View.VISIBLE);

        Style[] styles = Style.values();
        setupSegments(root.findViewById(R.id.toggle_style), STYLE_LABELS,
                BubblePrefs.getStyle(activity), i -> BubblePrefs.setStyle(activity, styles[i]));
        setupSegments(root.findViewById(R.id.toggle_size), SIZE_LABELS,
                BubblePrefs.getScaleIndex(activity), i -> BubblePrefs.setScaleIndex(activity, i));
        setupOpacity();
        setupShrink();
        refresh();
    }

    /** A single-choice segmented button: exactly one option is always selected. */
    private void setupSegments(MaterialButtonToggleGroup group, int[] labels, int selected,
                               java.util.function.IntConsumer onSelect) {
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialButton button = (MaterialButton) LayoutInflater.from(activity)
                    .inflate(R.layout.item_style_segment, group, false);
            button.setText(labels[i]);
            // The Material style keeps wide padding; trim it so labels fit.
            int pad = dp(4);
            button.setPaddingRelative(pad, button.getPaddingTop(), pad, button.getPaddingBottom());
            // One line; long labels ("Black & white", translations) shrink to fit.
            button.setMaxLines(1);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    button, 9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            ids[i] = View.generateViewId();
            button.setId(ids[i]);
            group.addView(button);
        }
        group.check(ids[Math.max(0, Math.min(selected, ids.length - 1))]);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    onSelect.accept(i);
                    changed();
                    return;
                }
            }
        });
    }

    private void setupOpacity() {
        Slider slider = root.findViewById(R.id.slider_opacity);
        TextView label = root.findViewById(R.id.text_opacity);
        int opacity = BubblePrefs.getOpacity(activity);
        slider.setValue(opacity);
        label.setText(activity.getString(R.string.style_opacity_value, opacity));
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser) return;
            BubblePrefs.setOpacity(activity, Math.round(value));
            label.setText(activity.getString(R.string.style_opacity_value, Math.round(value)));
            changed();
        });
    }

    private void setupShrink() {
        CompoundButton shrink = root.findViewById(R.id.switch_shrink);
        CompoundButton dot = root.findViewById(R.id.switch_shrink_dot);
        shrink.setChecked(BubblePrefs.isAutoShrink(activity));
        dot.setChecked(BubblePrefs.isShrinkDot(activity));
        dot.setEnabled(shrink.isChecked());
        shrink.setOnCheckedChangeListener((b, checked) -> {
            BubblePrefs.setAutoShrink(activity, checked);
            dot.setEnabled(checked);
            changed();
        });
        dot.setOnCheckedChangeListener((b, checked) -> {
            BubblePrefs.setShrinkDot(activity, checked);
            changed();
        });
    }

    /** A setting changed: update the preview and the live bubble. */
    private void changed() {
        InsertionAccessibilityService.notifyPrefsChanged();
        refresh();
    }

    // --- Preview ----------------------------------------------------------------------

    /** Rebuilds the preview (also call when the tab is shown). */
    void refresh() {
        BubbleHost.Settings s = BubblePrefs.load(activity);
        fillPreview(root.findViewById(R.id.preview_light), root.findViewById(R.id.preview_light_row), s, false);
        fillPreview(root.findViewById(R.id.preview_dark), root.findViewById(R.id.preview_dark_row), s, true);
    }

    private void fillPreview(FrameLayout panel, LinearLayout row, BubbleHost.Settings s, boolean night) {
        row.removeAllViews();
        int gap = dp(PREVIEW_GAP_DP);
        for (Mode mode : PREVIEW_MODES) {
            BubbleAppearance a = BubbleAppearance.resolve(s, night, mBlurSupported, mode, false);
            int size = dp(s.sizeDp);
            FrameLayout cell = new FrameLayout(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(gap / 2, 0, gap / 2, 0);
            cell.setLayoutParams(lp);

            View blurLayer = null;
            if (a.blur && Build.VERSION.SDK_INT >= 31) {
                // A blurred copy of the sample background, aligned to the panel,
                // stands in for the window blur the real bubble gets.
                blurLayer = new View(activity);
                blurLayer.setBackgroundResource(night
                        ? R.drawable.bg_style_sample_dark : R.drawable.bg_style_sample_light);
                blurLayer.setRenderEffect(RenderEffect.createBlurEffect(
                        dp(PREVIEW_BLUR_DP), dp(PREVIEW_BLUR_DP), Shader.TileMode.CLAMP));
                cell.addView(blurLayer, new FrameLayout.LayoutParams(1, 1));
                cell.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        int d = dp(a.discDp());
                        int l = (view.getWidth() - d) / 2;
                        int t = (view.getHeight() - d) / 2;
                        outline.setOval(l, t, l + d, t + d);
                    }
                });
                cell.setClipToOutline(true);
            }

            View bubble = LayoutInflater.from(activity).inflate(R.layout.bubble_overlay, cell, false);
            cell.addView(bubble);
            View disc = bubble.findViewById(R.id.bubble_disc);
            ImageView icon = bubble.findViewById(R.id.bubble_icon);
            ProgressBar progress = bubble.findViewById(R.id.bubble_progress);
            int discPx = dp(a.discDp());
            setSize(disc, discPx);
            setSize(icon, discPx / 2);
            setSize(progress, discPx / 2);
            boolean recording = mode == Mode.RECORDING_TAP;
            icon.setImageResource(recording ? R.drawable.ic_stop : R.drawable.ic_mic);
            icon.setVisibility(mode == Mode.PROCESSING ? View.GONE : View.VISIBLE);
            progress.setVisibility(mode == Mode.PROCESSING ? View.VISIBLE : View.GONE);
            mStyler.applyBubble(disc, icon, progress, a, mode, night, false);
            row.addView(cell);

            if (blurLayer != null) {
                final View layer = blurLayer;
                panel.post(() -> {
                    layer.setLayoutParams(new FrameLayout.LayoutParams(panel.getWidth(), panel.getHeight()));
                    layer.setTranslationX(-(row.getLeft() + cell.getLeft()));
                    layer.setTranslationY(-(row.getTop() + cell.getTop()));
                });
            }
        }
    }

    private static void setSize(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = px;
        lp.height = px;
        v.setLayoutParams(lp);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
