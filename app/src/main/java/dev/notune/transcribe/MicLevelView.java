package dev.notune.transcribe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class MicLevelView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float current = 0f;   // 0..1
    private float target = 0f;    // 0..1
    private ValueAnimator animator;

    public MicLevelView(Context c) { super(c); init(); }
    public MicLevelView(Context c, AttributeSet a) { super(c, a); init(); }
    public MicLevelView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // weiß, Alpha setzen wir dynamisch
    }


    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** level: 0..1 */
    public void setLevel(float level) {
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        target = level;

        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(current, target);
        animator.setDuration(60); // schnell, wirkt „live“
        animator.addUpdateListener(a -> {
            current = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float min = Math.min(getWidth(), getHeight()) / 2f;

        // Radius: Grundgröße + Pegel
        float base = min * 0.42f;
        float extra = min * 0.28f * current;
        float r = base + extra;

        // Innerer heller Kreis (macht Mitte heller)
        int alphaInner = (int) (50 + 90 * current);  // 50..140
        paint.setAlpha(alphaInner);
        canvas.drawCircle(cx, cy, r, paint);

        // Äußerer weicher Glow (größer, transparenter)
        int alphaOuter = (int) (18 + 45 * current);  // 18..63
        paint.setAlpha(alphaOuter);
        canvas.drawCircle(cx, cy, r * 1.25f, paint);
    }


}
