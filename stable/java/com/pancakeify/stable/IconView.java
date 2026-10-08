package com.pancakeify.stable;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A small view that draws one of the player's icons with vector paths (no drawables needed). */
public final class IconView extends View {
    public static final int CHEVRON_DOWN = 0, CHEVRON_UP = 1, PLAY = 2, PAUSE = 3, NEXT = 4, PREV = 5,
            SHUFFLE = 6, REPEAT = 7, REPEAT_ONE = 8, CHECK = 9, PLUS = 10, EXPAND = 11, COLLAPSE = 12,
            DEVICE = 13, SHARE = 14, QUEUE = 15, REFRESH = 16, TUNE = 17, GEAR = 18, MINUS = 19,
            REWIND = 20, FORWARD = 21, MORE = 22, HEART = 23;

    private int type;
    private int color = 0xFFFFFFFF;
    private int fillColor = 0;          // optional circle behind the icon (0 = none)
    private int ringColor = 0;          // optional outline circle
    private float glyph = 0.5f;         // glyph size as a fraction of the view
    private boolean dot;                // small "active" dot below (shuffle / repeat on)
    private float ringWidth = 0.04f;    // ring stroke as a fraction of the view
    private float fillRadius = 1f;      // 0..1: the filled circle grows from the centre (like animation)
    private float drawProgress = 1f;    // 0..1: how much of the check mark is drawn
    private float heartFill = 0f;      // 0..1: how much of the heart is filled (like animation)
    private float strokeW = 0.16f;      // glyph stroke in glyph units (1 = half the glyph size)
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public IconView(Context c, int type) {
        super(c);
        this.type = type;
        setClickable(true);
    }

    public IconView type(int t) { type = t; invalidate(); return this; }
    public IconView color(int c) { color = c; invalidate(); return this; }
    public IconView fill(int c) { fillColor = c; invalidate(); return this; }
    public IconView ring(int c) { ringColor = c; invalidate(); return this; }
    public IconView glyph(float g) { glyph = g; invalidate(); return this; }
    public IconView dot(boolean d) { dot = d; invalidate(); return this; }
    public IconView ringWidth(float f) { ringWidth = f; invalidate(); return this; }
    public IconView fillRadius(float f) { fillRadius = f; invalidate(); return this; }
    public IconView drawProgress(float f) { drawProgress = f; invalidate(); return this; }
    public IconView strokeWidth(float w) { strokeW = w; invalidate(); return this; }
    public IconView heartFill(float f) { heartFill = f; invalidate(); return this; }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, d = Math.min(w, h);
        if (fillColor != 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fillColor);
            cv.drawCircle(cx, cy, d / 2f * Math.max(0f, fillRadius), p);
        }
        if (ringColor != 0) {
            float s = d * ringWidth;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, s));
            p.setColor(ringColor);
            cv.drawCircle(cx, cy, d / 2f - p.getStrokeWidth() / 2f, p);
        }
        float u = d * glyph / 2f;               // unit = half glyph size
        cv.save();
        cv.translate(cx, cy);
        cv.scale(u, u);
        float stroke = strokeW;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (type) {
            case CHEVRON_DOWN:
                line(cv, stroke, -0.65f, -0.25f, 0f, 0.4f, 0.65f, -0.25f); break;
            case CHEVRON_UP:
                line(cv, stroke, -0.65f, 0.25f, 0f, -0.4f, 0.65f, 0.25f); break;
            case PLAY:
                fillPoly(cv, -0.5f, -0.75f, 0.8f, 0f, -0.5f, 0.75f); break;
            case PAUSE:
                fillRound(cv, -0.6f, -0.7f, -0.12f, 0.7f); fillRound(cv, 0.12f, -0.7f, 0.6f, 0.7f); break;
            case NEXT:
                fillPoly(cv, -0.75f, -0.65f, 0.35f, 0f, -0.75f, 0.65f);
                fillRound(cv, 0.45f, -0.65f, 0.7f, 0.65f); break;
            case PREV:
                fillPoly(cv, 0.75f, -0.65f, -0.35f, 0f, 0.75f, 0.65f);
                fillRound(cv, -0.7f, -0.65f, -0.45f, 0.65f); break;
            case REWIND:        // double triangle, Spicy Player style
                fillPoly(cv, 0.05f, -0.62f, -0.95f, 0f, 0.05f, 0.62f);
                fillPoly(cv, 0.95f, -0.62f, 0.05f, 0f, 0.95f, 0.62f); break;
            case FORWARD:
                fillPoly(cv, -0.05f, -0.62f, 0.95f, 0f, -0.05f, 0.62f);
                fillPoly(cv, -0.95f, -0.62f, -0.05f, 0f, -0.95f, 0.62f); break;
            case SHUFFLE:
                path.moveTo(-0.9f, 0.5f); path.lineTo(-0.35f, 0.5f);
                path.cubicTo(0.0f, 0.5f, 0.0f, -0.5f, 0.35f, -0.5f); path.lineTo(0.75f, -0.5f);
                strokePath(cv, stroke);
                line(cv, stroke, 0.45f, -0.8f, 0.8f, -0.5f, 0.45f, -0.2f);
                path.reset();
                path.moveTo(-0.9f, -0.5f); path.lineTo(-0.35f, -0.5f);
                path.cubicTo(0.0f, -0.5f, 0.0f, 0.5f, 0.35f, 0.5f); path.lineTo(0.75f, 0.5f);
                strokePath(cv, stroke);
                line(cv, stroke, 0.45f, 0.2f, 0.8f, 0.5f, 0.45f, 0.8f); break;
            case REPEAT:
            case REPEAT_ONE:
                path.moveTo(-0.8f, 0.05f); path.lineTo(-0.8f, -0.4f); path.lineTo(0.55f, -0.4f);
                strokePath(cv, stroke);
                line(cv, stroke, 0.25f, -0.7f, 0.6f, -0.4f, 0.25f, -0.1f);
                path.reset();
                path.moveTo(0.8f, -0.05f); path.lineTo(0.8f, 0.4f); path.lineTo(-0.55f, 0.4f);
                strokePath(cv, stroke);
                line(cv, stroke, -0.25f, 0.1f, -0.6f, 0.4f, -0.25f, 0.7f);
                if (type == REPEAT_ONE) {
                    p.setStyle(Paint.Style.FILL);
                    p.setTextSize(0.75f);
                    p.setFakeBoldText(true);
                    p.setTextAlign(Paint.Align.CENTER);
                    cv.drawText("1", 0f, 0.27f, p);
                    p.setFakeBoldText(false);
                }
                break;
            case CHECK: {
                Path chk = new Path();
                chk.moveTo(-0.6f, 0.05f); chk.lineTo(-0.2f, 0.45f); chk.lineTo(0.6f, -0.45f);
                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(chk, false);
                Path part = new Path();
                pm.getSegment(0f, pm.getLength() * Math.max(0f, Math.min(1f, drawProgress)), part, true);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(stroke, 0.3f));
                cv.drawPath(part, p);
                break;
            }
            case PLUS:
                line(cv, Math.max(stroke, 0.26f), -0.55f, 0f, 0.55f, 0f);
                line(cv, Math.max(stroke, 0.26f), 0f, -0.55f, 0f, 0.55f); break;
            case HEART: {                       // the classic Spotify heart: outline, filled when liked
                Path heart = new Path();
                heart.moveTo(0f, 0.88f);
                heart.cubicTo(-0.25f, 0.72f, -1.0f, 0.22f, -1.0f, -0.32f);
                heart.cubicTo(-1.0f, -0.82f, -0.38f, -1.0f, 0f, -0.52f);
                heart.cubicTo(0.38f, -1.0f, 1.0f, -0.82f, 1.0f, -0.32f);
                heart.cubicTo(1.0f, 0.22f, 0.25f, 0.72f, 0f, 0.88f);
                heart.close();
                p.setStrokeJoin(Paint.Join.ROUND);
                if (heartFill > 0.01f) {
                    p.setStyle(Paint.Style.FILL);
                    int base = p.getColor();
                    p.setColor((Math.round(Color.alpha(base) * Math.min(1f, heartFill)) << 24) | (base & 0xFFFFFF));
                    cv.drawPath(heart, p);
                    p.setColor(base);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(stroke, 0.19f));
                cv.drawPath(heart, p);
                break;
            }
            case MORE:
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(0f, -0.62f, 0.17f, p);
                cv.drawCircle(0f, 0f, 0.17f, p);
                cv.drawCircle(0f, 0.62f, 0.17f, p); break;
            case EXPAND:
                line(cv, 0.14f, 0.05f, -0.7f, 0.7f, -0.7f, 0.7f, -0.05f);
                line(cv, 0.14f, 0.7f, -0.7f, 0.12f, -0.12f);
                line(cv, 0.14f, -0.05f, 0.7f, -0.7f, 0.7f, -0.7f, 0.05f);
                line(cv, 0.14f, -0.7f, 0.7f, -0.12f, 0.12f); break;
            case COLLAPSE:
                line(cv, stroke, -0.65f, 0.25f, 0f, -0.4f, 0.65f, 0.25f); break;
            case DEVICE:
                rect.set(-0.8f, -0.7f, 0.8f, 0.3f);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.16f);
                cv.drawRoundRect(rect, 0.12f, 0.12f, p);
                line(cv, 0.2f, -1.0f, 0.72f, 1.0f, 0.72f); break;
            case SHARE:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.15f);
                cv.drawCircle(-0.6f, 0f, 0.26f, p);
                cv.drawCircle(0.6f, -0.65f, 0.26f, p);
                cv.drawCircle(0.6f, 0.65f, 0.26f, p);
                line(cv, 0.15f, -0.4f, -0.12f, 0.38f, -0.52f);
                line(cv, 0.15f, -0.4f, 0.12f, 0.38f, 0.52f); break;
            case QUEUE:
                rect.set(-0.85f, -0.9f, 0.85f, -0.35f);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.15f);
                cv.drawRoundRect(rect, 0.18f, 0.18f, p);
                line(cv, 0.15f, -0.85f, 0.12f, 0.85f, 0.12f);
                line(cv, 0.15f, -0.85f, 0.6f, 0.85f, 0.6f);
                line(cv, 0.15f, -0.85f, 1.05f, 0.85f, 1.05f); break;
            case REFRESH:
                arcArrow(cv, 200f, 140f); arcArrow(cv, 20f, 140f); break;
            case TUNE:
                line(cv, 0.14f, -0.85f, -0.55f, 0.85f, -0.55f);
                line(cv, 0.14f, -0.85f, 0f, 0.85f, 0f);
                line(cv, 0.14f, -0.85f, 0.55f, 0.85f, 0.55f);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(-0.25f, -0.55f, 0.27f, p);
                cv.drawCircle(0.35f, 0f, 0.27f, p);
                cv.drawCircle(-0.05f, 0.55f, 0.27f, p); break;
            case GEAR:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.3f);
                cv.drawCircle(0f, 0f, 0.5f, p);
                p.setStrokeWidth(0.32f);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    cv.drawLine((float) Math.cos(a) * 0.62f, (float) Math.sin(a) * 0.62f,
                            (float) Math.cos(a) * 0.9f, (float) Math.sin(a) * 0.9f, p);
                }
                p.setStrokeWidth(0.16f);
                cv.drawCircle(0f, 0f, 0.2f, p); break;
            case MINUS:
                line(cv, 0.18f, -0.55f, 0f, 0.55f, 0f); break;
            default: break;
        }
        cv.restore();
        if (dot) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            cv.drawCircle(cx, cy + d * glyph / 2f + d * 0.08f, d * 0.035f, p);
        }
    }

    /** Circular arc (start angle / sweep in degrees on a 0.65-radius circle) with an arrow head at its end. */
    private void arcArrow(Canvas cv, float start, float sweep) {
        Path a = new Path();
        rect.set(-0.65f, -0.65f, 0.65f, 0.65f);
        a.arcTo(rect, start, sweep);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.15f);
        cv.drawPath(a, p);
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure(a, false);
        float[] pos = new float[2], tan = new float[2];
        pm.getPosTan(pm.getLength(), pos, tan);
        double ang = Math.atan2(tan[1], tan[0]);
        float len = 0.38f;
        for (double d : new double[]{Math.PI * 0.8, -Math.PI * 0.8}) {
            cv.drawLine(pos[0], pos[1], pos[0] + (float) Math.cos(ang + d) * len,
                    pos[1] + (float) Math.sin(ang + d) * len, p);
        }
    }

    private void strokePath(Canvas cv, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        cv.drawPath(path, p);
    }

    /** Polyline through the given x,y pairs. */
    private void line(Canvas cv, float w, float... xy) {
        Path q = new Path();
        q.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) q.lineTo(xy[i], xy[i + 1]);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        cv.drawPath(q, p);
    }

    private void fillPoly(Canvas cv, float... xy) {
        Path q = new Path();
        q.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) q.lineTo(xy[i], xy[i + 1]);
        q.close();
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(0.18f);
        cv.drawPath(q, p);
    }

    private void fillRound(Canvas cv, float l, float t, float r, float b) {
        rect.set(l, t, r, b);
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(rect, 0.08f, 0.08f, p);
    }
}
