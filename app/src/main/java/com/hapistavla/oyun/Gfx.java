package com.hapistavla.oyun;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/** Tahta, ahşap doku ve pulların (taşların) çizimi. Hepsi bir kez bitmap'e çizilip yeniden kullanılır. */
public final class Gfx {
    private Gfx() {
    }

    /** Tahta teması. */
    public static final class Theme {
        public final String name;
        public final int unlockLevel;
        public final int frameLight;
        public final int frameDark;
        public final int fieldLight;
        public final int fieldDark;
        public final boolean felt;
        public final int triDarkBase;
        public final int triDarkTip;
        public final int triLightBase;
        public final int triLightTip;
        public final int bar;
        public final int inlay;

        Theme(String name, int unlockLevel, int frameLight, int frameDark, int fieldLight, int fieldDark,
              boolean felt, int triDarkBase, int triDarkTip, int triLightBase, int triLightTip, int bar, int inlay) {
            this.name = name;
            this.unlockLevel = unlockLevel;
            this.frameLight = frameLight;
            this.frameDark = frameDark;
            this.fieldLight = fieldLight;
            this.fieldDark = fieldDark;
            this.felt = felt;
            this.triDarkBase = triDarkBase;
            this.triDarkTip = triDarkTip;
            this.triLightBase = triLightBase;
            this.triLightTip = triLightTip;
            this.bar = bar;
            this.inlay = inlay;
        }
    }

    public static final Theme[] THEMES = {
        new Theme("Ceviz", 1,
            0xFF7A4A2B, 0xFF3E2213,          // çerçeve
            0xFFD9B27A, 0xFFB98B55, false,   // alan: bal rengi meşe
            0xFF5C2F17, 0xFF3A1B0B,          // koyu üçgenler
            0xFFF1DDB0, 0xFFD2B27C,          // açık üçgenler
            0xFF4A2814, 0xFFD4AF37),
        new Theme("Abanoz", 5,
            0xFF3A3028, 0xFF14100D,
            0xFFE3CDA0, 0xFFC7A976, false,
            0xFF1E1A17, 0xFF0B0908,
            0xFFF4EAD2, 0xFFD6C49B,
            0xFF1C1713, 0xFFC0C0C0),
        new Theme("Zümrüt", 10,
            0xFF6A3C22, 0xFF331B0E,
            0xFF1F6B4A, 0xFF124A33, true,
            0xFF7A1F25, 0xFF4B1015,
            0xFFE9D9A8, 0xFFC4AE72,
            0xFF3B2012, 0xFFD4AF37),
    };

    /** Tahta geometrisi (tahta bitmap'inin sol üst köşesine göre, piksel). */
    public static final class Geo {
        public float w;
        public float h;
        public float fr;      // çerçeve kalınlığı
        public float ix0;
        public float iy0;
        public float ix1;
        public float iy1;
        public float cw;      // bir noktanın genişliği
        public float triH;
        public float midH;
    }

    // ------------------------------------------------------------------
    // Renk yardımcıları
    // ------------------------------------------------------------------

    public static int mix(int a, int b, float t) {
        int aa = (a >>> 24) & 255;
        int ar = (a >> 16) & 255;
        int ag = (a >> 8) & 255;
        int ab = a & 255;
        int ba = (b >>> 24) & 255;
        int br = (b >> 16) & 255;
        int bg = (b >> 8) & 255;
        int bb = b & 255;
        int ra = (int) (aa + (ba - aa) * t);
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ------------------------------------------------------------------
    // Ahşap
    // ------------------------------------------------------------------

    /** Dikdörtgeni ahşap dokusuyla doldurur. horizontal: damarlar yatay mı? */
    public static void wood(Canvas c, RectF r, int light, int dark, long seed, boolean horizontal,
                            float intensity, float radius) {
        Random rnd = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        int mid = mix(light, dark, 0.45f);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(r.left, r.top, horizontal ? r.left : r.right, horizontal ? r.bottom : r.top,
                new int[] {light, mid, dark}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);

        float span = horizontal ? r.height() : r.width();
        float len = horizontal ? r.width() : r.height();
        // geniş ton bantları (tahta parçaları gibi)
        int bands = Math.max(3, (int) (span / 60f));
        for (int i = 0; i < bands; i++) {
            float pos = rnd.nextFloat() * span;
            float th = 14 + rnd.nextFloat() * 70;
            boolean lightBand = rnd.nextBoolean();
            p.setColor(lightBand ? 0xFFFFFFFF : 0xFF000000);
            p.setAlpha(6 + rnd.nextInt(14));
            if (horizontal) {
                c.drawRect(r.left, r.top + pos, r.right, r.top + pos + th, p);
            } else {
                c.drawRect(r.left + pos, r.top, r.left + pos + th, r.bottom, p);
            }
        }
        // damarlar
        p.setStyle(Paint.Style.STROKE);
        int n = (int) (span / 2.4f * intensity);
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            float pos = rnd.nextFloat() * span;
            boolean dk = rnd.nextFloat() < 0.72f;
            p.setStrokeWidth(0.6f + rnd.nextFloat() * 2.0f);
            p.setColor(dk ? mix(dark, 0xFF000000, 0.5f) : mix(light, 0xFFFFFFFF, 0.5f));
            p.setAlpha(dk ? 14 + rnd.nextInt(34) : 8 + rnd.nextInt(18));
            path.reset();
            float step = len / (5f + rnd.nextInt(4));
            float wob = (rnd.nextFloat() - 0.5f) * 5f;
            float cur = -step * rnd.nextFloat();
            if (horizontal) {
                path.moveTo(r.left + cur, r.top + pos);
            } else {
                path.moveTo(r.left + pos, r.top + cur);
            }
            while (cur < len + step) {
                float nx = cur + step;
                float nw = (rnd.nextFloat() - 0.5f) * 6f;
                if (horizontal) {
                    path.quadTo(r.left + cur + step / 2f, r.top + pos + wob, r.left + nx, r.top + pos + nw);
                } else {
                    path.quadTo(r.left + pos + wob, r.top + cur + step / 2f, r.left + pos + nw, r.top + nx);
                }
                wob = nw;
                cur = nx;
            }
            c.drawPath(path, p);
        }
        // birkaç göz (budak): sıkı halkalar
        int knots = (int) (len * span / 160000f * 1.5f);
        for (int k = 0; k < knots; k++) {
            float kx = r.left + rnd.nextFloat() * r.width();
            float ky = r.top + rnd.nextFloat() * r.height();
            float kr = 5 + rnd.nextFloat() * 9;
            for (int ring = 0; ring < 5; ring++) {
                p.setStrokeWidth(1.1f);
                p.setColor(mix(dark, 0xFF000000, 0.45f));
                p.setAlpha(46 - ring * 8);
                RectF e = new RectF(kx - kr * (1 + ring * 0.55f) * (horizontal ? 2.3f : 1f),
                        ky - kr * (1 + ring * 0.55f) * (horizontal ? 1f : 2.3f),
                        kx + kr * (1 + ring * 0.55f) * (horizontal ? 2.3f : 1f),
                        ky + kr * (1 + ring * 0.55f) * (horizontal ? 1f : 2.3f));
                c.drawOval(e, p);
            }
        }
        c.restore();
    }

    /** Keçe (çuha) doku: düz renk + ince gürültü. */
    private static void felt(Canvas c, RectF r, int light, int dark, long seed, float radius) {
        Random rnd = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                new int[] {light, dark}, new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);
        int dots = (int) (r.width() * r.height() / 90f);
        for (int i = 0; i < dots; i++) {
            p.setColor(rnd.nextBoolean() ? 0xFFFFFFFF : 0xFF000000);
            p.setAlpha(5 + rnd.nextInt(12));
            float x = r.left + rnd.nextFloat() * r.width();
            float y = r.top + rnd.nextFloat() * r.height();
            c.drawRect(x, y, x + 1.6f, y + 1.6f, p);
        }
        // kenarlarda hafif karartma (vinyet)
        p.setShader(new RadialGradient(r.centerX(), r.centerY(), Math.max(r.width(), r.height()) * 0.75f,
                new int[] {0x00000000, 0x00000000, 0x55000000}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);
        c.restore();
    }

    private static void fillField(Canvas c, RectF r, Theme th, long seed) {
        if (th.felt) {
            felt(c, r, th.fieldLight, th.fieldDark, seed, 6f);
        } else {
            wood(c, r, th.fieldLight, th.fieldDark, seed, true, 0.55f, 6f);
        }
    }

    /** İçe batık (çukur) kenar gölgesi. */
    private static void insetShadow(Canvas c, RectF r, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        // üst
        p.setShader(new LinearGradient(0, r.top, 0, r.top + w, 0x88000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.right, r.top + w, p);
        // sol
        p.setShader(new LinearGradient(r.left, 0, r.left + w, 0, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.left + w, r.bottom, p);
        // alt (hafif ışık)
        p.setShader(new LinearGradient(0, r.bottom - w * 0.6f, 0, r.bottom, 0x00FFFFFF, 0x30FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.bottom - w * 0.6f, r.right, r.bottom, p);
        // sağ
        p.setShader(new LinearGradient(r.right - w * 0.6f, 0, r.right, 0, 0x00FFFFFF, 0x28FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r.right - w * 0.6f, r.top, r.right, r.bottom, p);
        p.setShader(null);
    }

    /** Kabartma kenar: sol/üst açık, sağ/alt koyu çizgi. */
    public static void bevel(Canvas c, RectF r, float w, boolean raised) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(0xFFFFFFFF);
        p.setAlpha(raised ? 90 : 40);
        c.drawLine(r.left + w / 2, r.top + w / 2, r.right - w / 2, r.top + w / 2, p);
        c.drawLine(r.left + w / 2, r.top + w / 2, r.left + w / 2, r.bottom - w / 2, p);
        p.setColor(0xFF000000);
        p.setAlpha(raised ? 120 : 70);
        c.drawLine(r.left + w / 2, r.bottom - w / 2, r.right - w / 2, r.bottom - w / 2, p);
        c.drawLine(r.right - w / 2, r.top + w / 2, r.right - w / 2, r.bottom - w / 2, p);
    }

    private static void point(Canvas c, float left, float width, float baseY, float tipY, int cBase, int cTip) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path t = new Path();
        t.moveTo(left + width * 0.035f, baseY);
        t.lineTo(left + width * 0.965f, baseY);
        t.lineTo(left + width * 0.5f, tipY);
        t.close();
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, baseY, 0, tipY, new int[] {cBase, cTip}, new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(t, p);
        p.setShader(null);
        // sol kenarda ışık, sağ kenarda gölge
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.2f, width * 0.02f));
        p.setColor(0xFFFFFFFF);
        p.setAlpha(46);
        c.drawLine(left + width * 0.035f, baseY, left + width * 0.5f, tipY, p);
        p.setColor(0xFF000000);
        p.setAlpha(80);
        c.drawLine(left + width * 0.965f, baseY, left + width * 0.5f, tipY, p);
    }

    private static void screw(Canvas c, float x, float y, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f,
                new int[] {0xFFFFF2B8, 0xFFC9A24A, 0xFF6F5420}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, r * 0.22f));
        p.setColor(0xFF3A2C0C);
        c.drawLine(x - r * 0.6f, y - r * 0.35f, x + r * 0.6f, y + r * 0.35f, p);
    }

    private static void brassPlate(Canvas c, RectF r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {0xFFF6E3A1, 0xFFC9A24A, 0xFF8C6D26, 0xFFD9B95C}, new float[] {0f, 0.4f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        float rad = Math.min(r.width(), r.height()) * 0.18f;
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(0xFF4A3A12);
        c.drawRoundRect(r, rad, rad, p);
    }

    // ------------------------------------------------------------------
    // Tahta
    // ------------------------------------------------------------------

    /** Hareketsiz tahta katmanını (çerçeve, alan, noktalar, orta çubuk, taş tepsileri) çizer. */
    public static Bitmap buildBoard(Geo g, Theme th) {
        int w = (int) Math.ceil(g.w);
        int h = (int) Math.ceil(g.h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // dış çerçeve
        RectF outer = new RectF(0, 0, g.w, g.h);
        wood(c, outer, th.frameLight, th.frameDark, 11, true, 1.0f, g.fr * 0.8f);
        bevel(c, new RectF(1, 1, g.w - 1, g.h - 1), Math.max(2f, g.fr * 0.07f), true);
        // çerçeve içi ince altın şerit
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, g.fr * 0.05f));
        p.setColor(th.inlay);
        p.setAlpha(150);
        float inl = g.fr * 0.45f;
        c.drawRoundRect(new RectF(inl, inl, g.w - inl, g.h - inl), g.fr * 0.35f, g.fr * 0.35f, p);
        p.setAlpha(255);

        float cw = g.cw;
        // iki oynama alanı
        RectF left = new RectF(g.ix0, g.iy0, g.ix0 + 6f * cw, g.iy1);
        RectF right = new RectF(g.ix0 + 6.6f * cw, g.iy0, g.ix0 + 12.6f * cw, g.iy1);
        fillField(c, left, th, 21);
        fillField(c, right, th, 22);

        // noktalar (üçgenler)
        for (int col = 0; col < 12; col++) {
            float x = g.ix0 + col * cw + (col >= 6 ? 0.6f * cw : 0f);
            boolean dark = (col % 2 == 0);
            int cb = dark ? th.triDarkBase : th.triLightBase;
            int ct = dark ? th.triDarkTip : th.triLightTip;
            // üst sıra: tabanı üstte, ucu aşağıda
            boolean topDark = dark;
            point(c, x, cw, g.iy0, g.iy0 + g.triH * 0.94f,
                    topDark ? th.triDarkBase : th.triLightBase, topDark ? th.triDarkTip : th.triLightTip);
            // alt sıra: renkler ters, taban altta
            boolean botDark = !dark;
            point(c, x, cw, g.iy1, g.iy1 - g.triH * 0.94f,
                    botDark ? th.triDarkBase : th.triLightBase, botDark ? th.triDarkTip : th.triLightTip);
            if (cb == ct) {
                continue;
            }
        }
        insetShadow(c, left, g.fr * 0.55f);
        insetShadow(c, right, g.fr * 0.55f);

        // orta çubuk (menteşe)
        RectF bar = new RectF(g.ix0 + 6f * cw, g.iy0 - g.fr * 0.3f, g.ix0 + 6.6f * cw, g.iy1 + g.fr * 0.3f);
        wood(c, bar, mix(th.bar, 0xFFFFFFFF, 0.12f), mix(th.bar, 0xFF000000, 0.35f), 33, false, 0.9f, 0f);
        bevel(c, bar, Math.max(2f, cw * 0.05f), true);
        float hingeW = bar.width() * 0.8f;
        for (int i = 0; i < 2; i++) {
            float cy = g.iy0 + (i == 0 ? 0.24f : 0.76f) * (g.iy1 - g.iy0);
            RectF pl = new RectF(bar.centerX() - hingeW / 2f, cy - hingeW * 0.9f, bar.centerX() + hingeW / 2f, cy + hingeW * 0.9f);
            brassPlate(c, pl);
            screw(c, pl.centerX(), pl.top + hingeW * 0.32f, hingeW * 0.12f);
            screw(c, pl.centerX(), pl.bottom - hingeW * 0.32f, hingeW * 0.12f);
        }

        // taş toplama tepsileri
        float tx0 = g.ix0 + 12.6f * cw + cw * 0.08f;
        float tx1 = g.ix1;
        float midY = (g.iy0 + g.iy1) / 2f;
        RectF trayTop = new RectF(tx0, g.iy0, tx1, midY - g.fr * 0.15f);
        RectF trayBot = new RectF(tx0, midY + g.fr * 0.15f, tx1, g.iy1);
        for (RectF t : new RectF[] {trayTop, trayBot}) {
            wood(c, t, mix(th.frameDark, 0xFF000000, 0.15f), mix(th.frameDark, 0xFF000000, 0.5f), 44 + (int) t.top, false, 0.8f, 6f);
            insetShadow(c, t, g.fr * 0.5f);
            bevel(c, t, 2f, false);
        }
        // tepsi ayracı
        RectF sep = new RectF(tx0 - cw * 0.04f, midY - g.fr * 0.15f, tx1, midY + g.fr * 0.15f);
        wood(c, sep, th.frameLight, th.frameDark, 55, true, 0.8f, 0f);

        // çerçeve köşelerinde pirinç süs
        float sr = Math.max(3f, g.fr * 0.12f);
        screw(c, g.fr * 0.5f, g.fr * 0.5f, sr);
        screw(c, g.w - g.fr * 0.5f, g.fr * 0.5f, sr);
        screw(c, g.fr * 0.5f, g.h - g.fr * 0.5f, sr);
        screw(c, g.w - g.fr * 0.5f, g.h - g.fr * 0.5f, sr);
        return bmp;
    }

    // ------------------------------------------------------------------
    // Pullar (taşlar)
    // ------------------------------------------------------------------

    /** Pul bitmap'inin kenar boşluğu (gölge için). */
    public static int checkerPad(int radius) {
        return (int) Math.ceil(radius * 0.3f);
    }

    /** Yuvarlak, kabartmalı, parlak bir pul. Bitmap boyutu: 2 * (radius + checkerPad). */
    public static Bitmap checker(int radius, boolean white) {
        int pad = checkerPad(radius);
        int s = 2 * (radius + pad);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = s / 2f;
        float cy = s / 2f;
        float r = radius;

        // yumuşak gölge
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx + r * 0.04f, cy + r * 0.10f, r * 1.2f,
                new int[] {0xAA000000, 0x66000000, 0x00000000}, new float[] {0f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx + r * 0.04f, cy + r * 0.10f, r * 1.2f, p);

        // gövde
        int[] body = white
                ? new int[] {0xFFFFFFFF, 0xFFF4EEDD, 0xFFD9D0B9, 0xFFB5AB90}
                : new int[] {0xFF7C7C7C, 0xFF3C3C3C, 0xFF1B1B1B, 0xFF060606};
        p.setShader(new RadialGradient(cx - r * 0.34f, cy - r * 0.38f, r * 1.6f, body,
                new float[] {0f, 0.34f, 0.74f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);

        // dış kenar
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, r * 0.075f));
        p.setColor(white ? 0xFF8F8570 : 0xFF000000);
        c.drawCircle(cx, cy, r - p.getStrokeWidth() / 2f, p);
        // iç ışık kenarı
        p.setStrokeWidth(Math.max(1f, r * 0.035f));
        p.setColor(0xFFFFFFFF);
        p.setAlpha(white ? 140 : 70);
        c.drawCircle(cx, cy, r * 0.93f, p);
        p.setAlpha(255);

        // oyma halkalar (koyu çizgi + hemen altında açık çizgi)
        float[] rings = {0.80f, 0.60f};
        for (float k : rings) {
            p.setStrokeWidth(Math.max(1.2f, r * 0.04f));
            p.setColor(white ? 0xFF000000 : 0xFF000000);
            p.setAlpha(white ? 58 : 150);
            c.drawCircle(cx, cy, r * k, p);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(white ? 190 : 46);
            c.drawCircle(cx + r * 0.012f, cy + r * 0.03f, r * k, p);
        }
        p.setAlpha(255);

        // ortadaki çukur
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx + r * 0.10f, cy + r * 0.10f, r * 0.42f,
                white ? new int[] {0xFFFFFFFF, 0xFFE0D7C0} : new int[] {0xFF3E3E3E, 0xFF0C0C0C},
                new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 0.34f, p);
        p.setShader(null);

        // parlama
        RectF gl = new RectF(cx - r * 0.66f, cy - r * 0.82f, cx + r * 0.10f, cy - r * 0.22f);
        p.setShader(new RadialGradient(gl.centerX(), gl.centerY(), r * 0.45f,
                new int[] {white ? 0xCCFFFFFF : 0x99FFFFFF, 0x00FFFFFF}, new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        c.drawOval(gl, p);
        p.setShader(null);
        return bmp;
    }
}
