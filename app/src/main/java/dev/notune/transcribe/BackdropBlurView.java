package dev.notune.transcribe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * A container that blurs the content drawn behind it inside this window
 * ("backdrop blur") and paints a tint over it. It records the source view
 * into a RenderNode, offset to this view's position, with a blur RenderEffect
 * (Android 12+). This is in-app GPU rendering, so it works on every device,
 * including Samsung phones where cross-window blur is off. Before Android 12
 * it draws the fallback tint only.
 *
 * The source must not contain this view (use a sibling behind it), and it
 * should be a container whose children scroll, so that the scroll offset is
 * applied when it draws.
 */
public class BackdropBlurView extends FrameLayout {
    private View source;
    private RenderNode node;
    private float radiusPx;
    private float cornerPx;
    private int tint;
    private int fallbackTint;
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final int[] myLoc = new int[2];
    private final int[] srcLoc = new int[2];
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        // Redraw with the source's latest content (scrolling, animations).
        if (isShown()) invalidate();
        return true;
    };

    public BackdropBlurView(Context context) {
        this(context, null);
    }

    public BackdropBlurView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        });
        setClipToOutline(true);
    }

    /** @param source the view to blur, behind this one in the same window */
    public void setSource(View source, float radiusPx) {
        this.source = source;
        this.radiusPx = radiusPx;
        if (Build.VERSION.SDK_INT >= 31) {
            node = new RenderNode("backdrop");
            node.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
        }
        invalidate();
    }

    /**
     * @param tint         painted over the blur
     * @param fallbackTint painted instead when blur is not available (more opaque)
     */
    public void setTint(int tint, int fallbackTint) {
        this.tint = tint;
        this.fallbackTint = fallbackTint;
        invalidate();
    }

    public void setStroke(int color, float widthPx) {
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(widthPx);
        invalidate();
    }

    public void setCornerRadius(float px) {
        cornerPx = px;
        invalidateOutline();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        boolean blurred = false;
        if (node != null && canvas.isHardwareAccelerated() && source != null
                && source.getWidth() > 0 && w > 0 && h > 0) {
            getLocationInWindow(myLoc);
            source.getLocationInWindow(srcLoc);
            node.setPosition(0, 0, w, h);
            RecordingCanvas rc = node.beginRecording(w, h);
            rc.translate(srcLoc[0] - myLoc[0], srcLoc[1] - myLoc[1]);
            source.draw(rc);
            node.endRecording();
            canvas.drawRenderNode(node);
            blurred = true;
        }
        tintPaint.setColor(blurred ? tint : fallbackTint);
        canvas.drawRect(rect, tintPaint);
        canvas.restore();

        if (strokePaint.getStrokeWidth() > 0) {
            float inset = strokePaint.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, cornerPx - inset, cornerPx - inset, strokePaint);
        }
        super.dispatchDraw(canvas);
    }
}
