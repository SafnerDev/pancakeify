package com.pancakeify.stable;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/**
 * Animated "dynamic background" built from the track's cover: the cover is heavily blurred, then
 * warped by slowly drifting simplex noise, saturated and vignetted. This is an AGSL port of the
 * pipeline used by the Kawarp library (https://github.com/better-lyrics/kawarp, MIT licence,
 * (c) better-lyrics) that Spicy Lyrics uses for its dynamic background:
 * blur -> noise warp (large + medium octave, centre-weighted) -> scale/vignette/saturation/dither.
 */
public final class AuroraView extends View {
    // The simplex noise is Ashima Arts' / Stefan Gustavson's public-domain-style implementation,
    // identical to the one in Kawarp's warp pass.
    private static final String AGSL =
            "uniform shader tex1;\n" +
            "uniform shader tex2;\n" +
            "uniform float2 res;\n" +
            "uniform float time;\n" +
            "uniform float mixAmt;\n" +
            "uniform float intensity;\n" +
            "uniform float saturation;\n" +
            "uniform float brightness;\n" +
            "float3 mod289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n" +
            "float2 mod289_2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }\n" +
            "float3 permute(float3 x) { return mod289_3(((x * 34.0) + 1.0) * x); }\n" +
            "float snoise(float2 v) {\n" +
            "  float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);\n" +
            "  float2 i = floor(v + dot(v, C.yy));\n" +
            "  float2 x0 = v - i + dot(i, C.xx);\n" +
            "  float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);\n" +
            "  float4 x12 = x0.xyxy + C.xxzz;\n" +
            "  x12.xy -= i1;\n" +
            "  i = mod289_2(i);\n" +
            "  float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));\n" +
            "  float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);\n" +
            "  m = m * m; m = m * m;\n" +
            "  float3 x = 2.0 * fract(p * C.www) - 1.0;\n" +
            "  float3 h = abs(x) - 0.5;\n" +
            "  float3 ox = floor(x + 0.5);\n" +
            "  float3 a0 = x - ox;\n" +
            "  m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);\n" +
            "  float3 g;\n" +
            "  g.x = a0.x * x0.x + h.x * x0.y;\n" +
            "  g.yz = a0.yz * x12.xz + h.yz * x12.yw;\n" +
            "  return 130.0 * dot(m, g);\n" +
            "}\n" +
            "half4 main(float2 frag) {\n" +
            "  float2 uv = frag / res;\n" +
            "  float t = time * 0.05;\n" +
            "  float2 c = uv - 0.5;\n" +
            "  float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(c));\n" +
            "  float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));\n" +
            "  float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));\n" +
            "  float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));\n" +
            "  float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));\n" +
            "  float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;\n" +
            "  float2 w = clamp(uv + warp * intensity, 0.0, 1.0);\n" +
            "  half4 col = mix(tex1.eval(w * res), tex2.eval(w * res), half(mixAmt));\n" +
            "  float vig = 1.0 - dot(c, c) * 0.3;\n" +
            "  col.rgb *= half(vig * brightness);\n" +
            "  half gray = dot(col.rgb, half3(0.299, 0.587, 0.114));\n" +
            "  col.rgb = mix(half3(gray), col.rgb, half(saturation));\n" +
            "  float n = fract(sin(dot(frag + float2(time, time), float2(12.9898, 78.233))) * 43758.5453);\n" +
            "  col.rgb += half((n - 0.5) * 0.03);\n" +
            "  col.a = 1.0;\n" +
            "  return col;\n" +
            "}\n";

    private RuntimeShader shader;
    private final Paint paint = new Paint();
    private BitmapShader texA, texB;
    private Bitmap bmpA, bmpB;
    private float mix = 1f;
    private long mixStart = -1;
    private float animTime;               // shader time: only advances while the music plays
    private long lastFrameMs = -1;
    private float motion = 1f;            // 0..1, eases to 0 on pause and back to 1 on play
    private boolean playing = true;
    private float speed = 1.1f;           // multiplies Kawarp's time; slow, calm drift
    private float saturation = 1.5f;      // Spicy default
    private float warpIntensity = 1f;
    private boolean failed;

    /** The background is so soft that it is rendered at 1/3 size and scaled up: ~9x less GPU work, same look. */
    private static final int DOWNSCALE = 3;

    public AuroraView(Context c) {
        super(c);
        setBackgroundColor(0xFF0A0A0A);
        setPivotX(0f);
        setPivotY(0f);
        setScaleX(DOWNSCALE);
        setScaleY(DOWNSCALE);
        // one more soft Gaussian over the whole shader output (36dp on screen = 12dp at this size)
        float px = 36f / DOWNSCALE * c.getResources().getDisplayMetrics().density;
        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP));
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(Math.max(1, (w + DOWNSCALE - 1) / DOWNSCALE), Math.max(1, (h + DOWNSCALE - 1) / DOWNSCALE));
    }

    /** Sets the cover to derive the background from (cross-fades from the previous one). */
    public void setCover(Bitmap cover) {
        if (cover == null) return;
        Bitmap soft = soften(cover);
        if (bmpA == null) {
            bmpA = bmpB = soft;
            texA = texB = makeShader(soft);
            mix = 1f;
        } else {
            // current picture becomes "A", the new one "B", then fade A -> B
            Bitmap shown = mix >= 0.5f ? bmpB : bmpA;
            BitmapShader shownTex = mix >= 0.5f ? texB : texA;
            bmpA = shown; texA = shownTex;
            bmpB = soft; texB = makeShader(soft);
            mix = 0f;
            mixStart = SystemClock.uptimeMillis();
        }
        invalidate();
    }

    public void setSaturation(float s) { saturation = s; }

    /** The background only drifts while the song plays; it eases to a stop on pause. */
    public void setPlaying(boolean p) {
        if (p == playing) return;
        playing = p;
        lastFrameMs = -1;
        invalidate();
    }

    private BitmapShader makeShader(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Very heavy blur: average down by halving to a handful of pixels, then smooth back up in ONE
     * bilinear step so the colours melt into each other (no visible cover structure left).
     */
    private static Bitmap soften(Bitmap src) {
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, false);
        while (b.getWidth() > 8 && b.getHeight() > 8) {
            b = Bitmap.createScaledBitmap(b, Math.max(4, b.getWidth() / 2), Math.max(4, b.getHeight() / 2), true);
        }
        // doubling steps (each one bilinear) approximate a smooth spline -> no visible banding rings
        while (b.getWidth() < 192) {
            b = Bitmap.createScaledBitmap(b, b.getWidth() * 2, b.getHeight() * 2, true);
        }
        return b;
    }

    @Override protected void onDraw(Canvas cv) {
        if (bmpA == null || failed) return;
        float w = getWidth(), h = getHeight();
        try {
            if (shader == null) shader = new RuntimeShader(AGSL);
            if (mixStart >= 0) {
                float f = (SystemClock.uptimeMillis() - mixStart) / 900f;
                mix = Math.min(1f, f);
                if (f >= 1f) mixStart = -1;
            }
            Matrix m = new Matrix();
            m.setScale(w / bmpA.getWidth(), h / bmpA.getHeight());
            texA.setLocalMatrix(m);
            Matrix m2 = new Matrix();
            m2.setScale(w / bmpB.getWidth(), h / bmpB.getHeight());
            texB.setLocalMatrix(m2);
            shader.setInputShader("tex1", texA);
            shader.setInputShader("tex2", texB);
            shader.setFloatUniform("res", w, h);
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameMs < 0 ? 0f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
            lastFrameMs = now;
            motion += ((playing ? 1f : 0f) - motion) * Math.min(1f, dt * 2.5f);
            if (!playing && motion < 0.002f) motion = 0f;
            animTime += dt * speed * motion;
            shader.setFloatUniform("time", animTime);
            shader.setFloatUniform("mixAmt", mix);
            shader.setFloatUniform("intensity", warpIntensity);
            shader.setFloatUniform("saturation", saturation);
            shader.setFloatUniform("brightness", 0.8f);
            paint.setShader(shader);
            cv.drawRect(0, 0, w, h, paint);
            if (playing || motion > 0f || mixStart >= 0) postInvalidateOnAnimation();   // paused: stop redrawing
        } catch (Throwable t) {
            failed = true;
            Log.e(PancakeBootstrap.TAG, "AuroraView shader failed", t);
            setBackgroundColor(0xFF1B3A28);
        }
    }
}
