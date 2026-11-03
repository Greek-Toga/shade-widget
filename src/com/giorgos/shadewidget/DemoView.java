package com.giorgos.shadewidget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Looping illustration of the "delete from the widget" feature:
 * a collapsed group, a tap on the chevron, the group expands with an X on each notification,
 * a tap on one X, and that notification slides away. Drawn with the widget's own colors.
 */
public class DemoView extends View {

    private static final long LOOP_MS = 5200;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private ValueAnimator anim;
    private float t;

    private final int cCard, cPrimary, cSecondary, cAccent, cPill, cOnPill, cOnAccent;

    public DemoView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        cCard = c.getColorStateList(R.color.card).getDefaultColor() | 0xFF000000;
        cPrimary = c.getColor(R.color.text_primary);
        cSecondary = c.getColor(R.color.text_secondary);
        cAccent = c.getColor(R.color.icon_circle);
        cOnAccent = c.getColor(R.color.on_icon_circle);
        cPill = c.getColor(R.color.pill_bg);
        cOnPill = c.getColor(R.color.on_pill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setContentDescription("Animation: expand a group, then tap the X on a notification to delete it.");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(LOOP_MS);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                t = (float) va.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(250 * dp));
    }

    /** 0 before a, 1 after b, eased in between. */
    private static float ease(float t, float a, float b) {
        if (t <= a) return 0f;
        if (t >= b) return 1f;
        float x = (t - a) / (b - a);
        return x * x * (3 - 2 * x);
    }

    private static float lerp(float a, float b, float f) { return a + (b - a) * f; }

    private static int alpha(int color, float a) {
        int base = (color >>> 24);
        return (color & 0x00FFFFFF) | (Math.round(base * Math.max(0, Math.min(1, a))) << 24);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float pad = 12 * dp;
        float left = pad, right = w - pad, top = 10 * dp;

        // Timeline
        float expand = ease(t, 0.18f, 0.32f);
        float slide = ease(t, 0.52f, 0.64f);
        float close = ease(t, 0.62f, 0.72f);
        float fadeOut = ease(t, 0.86f, 0.93f);
        float fadeIn = ease(t, 0.93f, 1.0f);
        float global = t >= 0.93f ? fadeIn : 1f - fadeOut;
        if (t >= 0.93f) { expand = 0; slide = 0; close = 0; }

        float headerH = 56 * dp, lineH = 24 * dp, rowH = 58 * dp;
        float collapsedH = headerH + 2 * lineH + 12 * dp;
        float expandedH = headerH + rowH * (3 - close) + 8 * dp;
        float cardH = lerp(collapsedH, expandedH, expand);

        // Card
        fill.setColor(alpha(cCard, global));
        r.set(left, top, right, top + cardH);
        c.drawRoundRect(r, 20 * dp, 20 * dp, fill);

        // Header: app icon, app name, count + chevron pill
        float iconCx = left + 14 * dp + 18 * dp, iconCy = top + headerH / 2;
        fill.setColor(alpha(cAccent, global));
        c.drawCircle(iconCx, iconCy, 18 * dp, fill);
        fill.setColor(alpha(cOnAccent, global));
        c.drawCircle(iconCx, iconCy, 6 * dp, fill);
        bar(c, iconCx + 30 * dp, iconCy - 10 * dp, 90 * dp, 9 * dp, cPrimary, 0.7f * global);
        bar(c, iconCx + 30 * dp, iconCy + 5 * dp, 36 * dp, 7 * dp, cSecondary, 0.45f * global);

        float pillW = 58 * dp, pillH = 30 * dp;
        float pillL = right - 12 * dp - pillW, pillT = iconCy - pillH / 2;
        fill.setColor(alpha(cPill, global));
        r.set(pillL, pillT, pillL + pillW, pillT + pillH);
        c.drawRoundRect(r, pillH / 2, pillH / 2, fill);
        text.setColor(alpha(cOnPill, global));
        text.setTextSize(13 * dp);
        String count = slide > 0.5f ? "2" : "3";
        c.drawText(count, pillL + 20 * dp, pillT + pillH / 2 + 4.5f * dp, text);
        float chX = pillL + 40 * dp, chY = pillT + pillH / 2;
        stroke.setColor(alpha(cOnPill, global));
        stroke.setStrokeWidth(2 * dp);
        c.save();
        c.rotate(180 * expand, chX, chY);
        path.reset();
        path.moveTo(chX - 5 * dp, chY - 2.5f * dp);
        path.lineTo(chX, chY + 2.5f * dp);
        path.lineTo(chX + 5 * dp, chY - 2.5f * dp);
        c.drawPath(path, stroke);
        c.restore();

        float contentL = left + 60 * dp;
        float y0 = top + headerH;

        // Collapsed preview lines (fade out while expanding)
        float lineA = (1 - expand) * global;
        if (lineA > 0.01f) {
            for (int i = 0; i < 2; i++) {
                float cy = y0 + lineH * i + lineH / 2;
                bar(c, contentL, cy - 4 * dp, 60 * dp, 8 * dp, cPrimary, 0.55f * lineA);
                bar(c, contentL + 68 * dp, cy - 4 * dp, right - contentL - 120 * dp, 8 * dp, cSecondary, 0.35f * lineA);
            }
        }

        // Expanded rows, each with an X (fade in while expanding)
        float rowA = expand * global;
        float xCx = right - 12 * dp - 15 * dp;
        float tapX2 = xCx, tapY2 = 0;
        if (rowA > 0.01f) {
            float y = y0;
            c.save();
            c.clipRect(left, top, right, top + cardH);
            for (int i = 0; i < 3; i++) {
                float h = rowH;
                float a = rowA;
                float dx = 0;
                if (i == 1) {
                    h = rowH * (1 - close);
                    a = rowA * (1 - slide);
                    dx = slide * (right - left);
                    tapY2 = y + rowH / 2;
                }
                if (h > 0.5f && a > 0.01f) {
                    float cy = y + rowH / 2 - (rowH - h) / 2;
                    c.save();
                    c.translate(dx, 0);
                    bar(c, contentL, cy - 12 * dp, 110 * dp, 9 * dp, cPrimary, 0.7f * a);
                    bar(c, contentL, cy + 4 * dp, right - contentL - 70 * dp, 8 * dp, cSecondary, 0.4f * a);
                    fill.setColor(alpha(cPill, a));
                    c.drawCircle(xCx, cy, 15 * dp, fill);
                    stroke.setColor(alpha(cOnPill, a));
                    stroke.setStrokeWidth(1.8f * dp);
                    float k = 4.5f * dp;
                    c.drawLine(xCx - k, cy - k, xCx + k, cy + k, stroke);
                    c.drawLine(xCx + k, cy - k, xCx - k, cy + k, stroke);
                    c.restore();
                }
                y += h;
            }
            c.restore();
        }

        // Finger: moves to the chevron and taps, then moves to the second X and taps.
        float xTapT = 0.46f;
        if (tapY2 == 0) tapY2 = y0 + rowH * 1.5f;
        float fx, fy, fa;
        if (t < 0.06f || t >= 0.86f) {
            fa = 0;
            fx = chX; fy = chY;
        } else if (t < 0.24f) {
            float m = ease(t, 0.06f, 0.13f);
            fx = lerp(chX + 30 * dp, chX, m);
            fy = lerp(chY + 70 * dp, chY, m);
            fa = ease(t, 0.06f, 0.10f) * (1 - ease(t, 0.20f, 0.24f));
        } else if (t < 0.58f) {
            float m = ease(t, 0.34f, 0.44f);
            fx = lerp(chX, tapX2, m);
            fy = lerp(chY + 40 * dp, tapY2, m);
            fa = ease(t, 0.32f, 0.36f) * (1 - ease(t, 0.52f, 0.58f));
        } else {
            fa = 0; fx = tapX2; fy = tapY2;
        }
        if (fa > 0.01f) {
            fill.setColor(alpha(cPrimary, 0.28f * fa * global));
            c.drawCircle(fx, fy, 16 * dp, fill);
        }
        drawRipple(c, chX, chY, ease(t, 0.14f, 0.24f), global);
        drawRipple(c, tapX2, tapY2, ease(t, xTapT, xTapT + 0.08f), global);
    }

    private void drawRipple(Canvas c, float x, float y, float p, float global) {
        if (p <= 0 || p >= 1) return;
        stroke.setColor(alpha(cAccent, (1 - p) * 0.8f * global));
        stroke.setStrokeWidth(2 * dp);
        c.drawCircle(x, y, lerp(8 * dp, 26 * dp, p), stroke);
    }

    private void bar(Canvas c, float x, float y, float w, float h, int color, float a) {
        if (w <= 0) return;
        fill.setColor(alpha(color, a));
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, h / 2, h / 2, fill);
    }
}
