package com.hapistavla.oyun;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;

/** Tavla tahtasının çizimi ve dokunma girdisi. Oyun mantığı Game sınıfındadır. */
public class TahtaView extends View implements Game.Host {

    public interface Callbacks {
        void onMenu();

        /** Menü ekranındaki bir düğmeye basıldı. */
        void onMenuItem(int id);

        void onRules();

        void onOnlineEvent(int type, String a, String b);

        /** Oyun bitti; sonuç ekranında gösterilecek ek metni döndürür (boş olabilir). */
        String onGameOver(Game.Result r);
    }

    // buton kimlikleri
    private static final int B_MENU = 0;
    private static final int B_UNDO = 1;
    private static final int B_HINT = 2;
    private static final int B_RULES = 3;
    private static final int B_COUNT = 4;

    /** Menü ekranındaki bir düğme. */
    public static final class MenuItem {
        public final int id;
        public final String label;
        public final boolean primary;

        public MenuItem(int id, String label, boolean primary) {
            this.id = id;
            this.label = label;
            this.primary = primary;
        }
    }

    private boolean menuOpen = false;
    private String menuTitle = "";
    private String menuSub = "";
    private List<MenuItem> menuItems = new ArrayList<MenuItem>();
    private final List<RectF> menuRects = new ArrayList<RectF>();

    public void showMenu(String title, String sub, List<MenuItem> items) {
        menuTitle = title;
        menuSub = sub;
        menuItems = items;
        menuOpen = true;
        invalidate();
    }

    public void hideMenu() {
        menuOpen = false;
        invalidate();
    }

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public final Game game;
    private Callbacks cb;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object timerToken = new Object();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // yerleşim
    private int insL;
    private int insT;
    private int insR;
    private boolean layoutReady = false;
    private float bx0;
    private float by0;
    private float bx1;
    private float by1;
    private float cw;
    private float triH;
    private float midH;
    private float r;
    private float ix0;
    private float iy0;
    private float iy1;
    private float u;
    private final RectF leftPanel = new RectF();
    private final RectF rightPanel = new RectF();
    private final RectF statusRect = new RectF();
    private final RectF[] btn = new RectF[B_COUNT];
    private final RectF rollBtn = new RectF();
    private final RectF overNew = new RectF();
    private final RectF overMenu = new RectF();
    private final RectF card0 = new RectF();
    private final RectF card1 = new RectF();

    // önbellek
    private Gfx.Geo geo = new Gfx.Geo();
    private Bitmap boardBmp;
    private Bitmap whiteBmp;
    private Bitmap blackBmp;
    private int chkSize;
    private int themeIndex = 0;
    private Shader bgShader;

    private float downX;
    private float downY;

    private static final float[][][] PIPS = new float[][][] {
        {},
        {{0.5f, 0.5f}},
        {{0.27f, 0.27f}, {0.73f, 0.73f}},
        {{0.27f, 0.27f}, {0.5f, 0.5f}, {0.73f, 0.73f}},
        {{0.27f, 0.27f}, {0.73f, 0.27f}, {0.27f, 0.73f}, {0.73f, 0.73f}},
        {{0.27f, 0.27f}, {0.73f, 0.27f}, {0.5f, 0.5f}, {0.27f, 0.73f}, {0.73f, 0.73f}},
        {{0.27f, 0.27f}, {0.73f, 0.27f}, {0.27f, 0.5f}, {0.73f, 0.5f}, {0.27f, 0.73f}, {0.73f, 0.73f}}
    };

    public TahtaView(Context context) {
        super(context);
        for (int i = 0; i < btn.length; i++) {
            btn[i] = new RectF();
        }
        text.setTypeface(Typeface.DEFAULT_BOLD);
        game = new Game(this);
    }

    private Sfx sfx;

    public void setSfx(Sfx s) {
        this.sfx = s;
    }

    public int getTheme() {
        return themeIndex;
    }

    public void setCallbacks(Callbacks c) {
        this.cb = c;
    }

    public void setTheme(int index) {
        if (index >= 0 && index < Gfx.THEMES.length && index != themeIndex) {
            themeIndex = index;
            rebuildBitmaps();
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Game.Host
    // ------------------------------------------------------------------

    @Override
    public void postTimer(Runnable run, long delayMs) {
        handler.postAtTime(run, timerToken, SystemClock.uptimeMillis() + delayMs);
    }

    @Override
    public void cancelTimers() {
        handler.removeCallbacksAndMessages(timerToken);
    }

    @Override
    public void postUi(Runnable run) {
        handler.post(run);
    }

    @Override
    public void changed() {
        invalidate();
    }

    @Override
    public void onlineEvent(int type, String a, String b) {
        if (cb != null) {
            cb.onOnlineEvent(type, a, b);
        }
    }

    @Override
    public void gameOver(Game.Result res) {
        if (cb != null) {
            String extra = cb.onGameOver(res);
            game.overExtra = extra == null ? "" : extra;
        }
    }

    @Override
    public void sound(int kind) {
        if (sfx != null) {
            sfx.play(kind);
        }
    }

    @Override
    public void haptic() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    // ------------------------------------------------------------------
    // Yerleşim
    // ------------------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int l = insets.getSystemWindowInsetLeft();
        int t = insets.getSystemWindowInsetTop();
        int rr = insets.getSystemWindowInsetRight();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = insets.getDisplayCutout();
            if (dc != null) {
                l = Math.max(l, dc.getSafeInsetLeft());
                t = Math.max(t, dc.getSafeInsetTop());
                rr = Math.max(rr, dc.getSafeInsetRight());
            }
        }
        insL = l;
        insT = t;
        insR = rr;
        computeLayout();
        invalidate();
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void computeLayout() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float pad = Math.min(w, h) * 0.012f;
        float x0 = insL + pad;
        float y0 = insT + pad;
        float x1 = w - insR - pad;
        float y1 = h - pad;
        float statusH = (y1 - y0) * 0.07f;
        float boardH = (y1 - y0) - statusH - pad;
        float availW = x1 - x0;
        float boardW = Math.min(boardH * 1.5f, availW * 0.66f);
        bx0 = x0 + (availW - boardW) / 2f;
        by0 = y0;
        bx1 = bx0 + boardW;
        by1 = by0 + boardH;
        u = boardH / 870f;

        geo = new Gfx.Geo();
        geo.w = boardW;
        geo.h = boardH;
        geo.fr = boardH * 0.04f;
        geo.ix0 = geo.fr;
        geo.iy0 = geo.fr;
        geo.ix1 = boardW - geo.fr;
        geo.iy1 = boardH - geo.fr;
        geo.cw = (geo.ix1 - geo.ix0) / 14f;
        float innerH = geo.iy1 - geo.iy0;
        geo.triH = innerH * 0.44f;
        geo.midH = innerH - 2f * geo.triH;
        cw = geo.cw;
        triH = geo.triH;
        midH = geo.midH;
        ix0 = bx0 + geo.ix0;
        iy0 = by0 + geo.iy0;
        iy1 = by0 + geo.iy1;
        r = Math.min(cw * 0.46f, triH / 5.6f);

        leftPanel.set(x0, by0, bx0 - pad, by1);
        rightPanel.set(bx1 + pad, by0, x1, by1);
        float sw = Math.min(availW, boardW * 1.3f);
        statusRect.set((x0 + x1) / 2f - sw / 2f, by1 + pad, (x0 + x1) / 2f + sw / 2f, y1);

        // sol panel: iki kart
        float ph = by1 - by0;
        float cardH = Math.min(ph * 0.40f, leftPanel.width() * 0.9f);
        card0.set(leftPanel.left, by0, leftPanel.right, by0 + cardH);
        card1.set(leftPanel.left, by1 - cardH, leftPanel.right, by1);

        // sağ panel: butonlar
        float bh = Math.min(ph * 0.12f, rightPanel.width() * 0.34f);
        float gap = bh * 0.2f;
        for (int i = 0; i < B_COUNT; i++) {
            btn[i].set(rightPanel.left, by0 + i * (bh + gap), rightPanel.right, by0 + i * (bh + gap) + bh);
        }

        bgShader = new LinearGradient(0, 0, 0, h, new int[] {0xFF241913, 0xFF120D0A},
                new float[] {0f, 1f}, Shader.TileMode.CLAMP);
        rebuildBitmaps();
        layoutReady = true;
    }

    private void rebuildBitmaps() {
        if (geo == null || geo.w <= 0 || geo.h <= 0) {
            return;
        }
        boardBmp = Gfx.buildBoard(geo, Gfx.THEMES[themeIndex]);
        int rad = Math.max(8, (int) r);
        whiteBmp = Gfx.checker(rad, true);
        blackBmp = Gfx.checker(rad, false);
        chkSize = whiteBmp.getWidth();
    }

    // ------------------------------------------------------------------
    // Koordinatlar
    // ------------------------------------------------------------------

    private boolean flip() {
        return game.viewColor() == Board.BLACK;
    }

    /** Mantıksal nokta <-> ekrandaki nokta (kendi tersi). */
    private int disp(int pt) {
        return flip() ? 25 - pt : pt;
    }

    private int colOf(int dp) {
        return dp >= 13 ? dp - 13 : 12 - dp;
    }

    private float colLeft(int col) {
        return ix0 + col * cw + (col >= 6 ? 0.6f * cw : 0f);
    }

    private float ptCx(int pt) {
        return colLeft(colOf(disp(pt))) + cw / 2f;
    }

    private boolean ptTop(int pt) {
        return disp(pt) >= 13;
    }

    private float stackSpacing(int total) {
        float sp = 2f * r * 0.98f;
        float avail = triH - 2f * r - 8f * u;
        if (total > 1 && (total - 1) * sp > avail) {
            sp = avail / (total - 1);
        }
        return sp;
    }

    private float slotY(int pt, int k, float sp) {
        float off = r + 5f * u + k * sp;
        return ptTop(pt) ? iy0 + off : iy1 - off;
    }

    private int pointAt(float x, float y) {
        float rel = x - ix0;
        int col;
        if (rel < 0) {
            return 0;
        } else if (rel < 6f * cw) {
            col = (int) (rel / cw);
        } else if (rel < 6.6f * cw) {
            return 0;
        } else if (rel < 12.6f * cw) {
            col = (int) ((rel - 0.6f * cw) / cw);
        } else {
            return 0;
        }
        if (col < 0 || col > 11 || y < iy0 || y > iy1) {
            return 0;
        }
        boolean top = y < (iy0 + iy1) / 2f;
        int dp = top ? 13 + col : 12 - col;
        return disp(dp);
    }

    // ------------------------------------------------------------------
    // Dokunma
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        } else if (a == MotionEvent.ACTION_UP) {
            float dx = e.getX() - downX;
            float dy = e.getY() - downY;
            if (dx * dx + dy * dy < 40f * 40f * u * u + 900f) {
                handleTap(e.getX(), e.getY());
            }
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void handleTap(float x, float y) {
        if (!layoutReady) {
            return;
        }
        if (menuOpen) {
            for (int i = 0; i < menuRects.size() && i < menuItems.size(); i++) {
                if (menuRects.get(i).contains(x, y)) {
                    if (cb != null) {
                        cb.onMenuItem(menuItems.get(i).id);
                    }
                    return;
                }
            }
            return;
        }
        if (game.phase == Game.PH_OVER) {
            if (overNew.contains(x, y)) {
                game.newGameRequest();
            } else if (overMenu.contains(x, y) && cb != null) {
                cb.onMenu();
            }
            return;
        }
        if (btn[B_MENU].contains(x, y)) {
            if (cb != null) {
                cb.onMenu();
            }
            return;
        }
        if (btn[B_UNDO].contains(x, y)) {
            game.undo();
            return;
        }
        if (btn[B_HINT].contains(x, y)) {
            game.requestHint();
            return;
        }
        if (btn[B_RULES].contains(x, y)) {
            if (cb != null) {
                cb.onRules();
            }
            return;
        }
        boolean onBoard = x >= bx0 && x <= bx1 && y >= by0 && y <= by1;
        if (!onBoard) {
            return;
        }
        if (game.canRoll()) {
            game.tapRoll();
            return;
        }
        if (x >= ix0 + 12.6f * cw) {
            game.tapTray();
            return;
        }
        int pt = pointAt(x, y);
        if (pt != 0) {
            game.tapPoint(pt);
        }
    }

    // ------------------------------------------------------------------
    // Çizim yardımcıları
    // ------------------------------------------------------------------

    private void centerText(Canvas c, String s, RectF rc, int color, float size) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(color);
        text.setTextSize(size);
        float tw = text.measureText(s);
        float maxW = rc.width() * 0.94f;
        if (tw > maxW && tw > 0) {
            text.setTextSize(size * maxW / tw);
        }
        float yy = rc.centerY() - (text.descent() + text.ascent()) / 2f;
        c.drawText(s, rc.centerX(), yy, text);
    }

    private void leftText(Canvas c, String s, float x, float baseline, int color, float size, float maxW) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color);
        text.setTextSize(size);
        float tw = text.measureText(s);
        if (tw > maxW && tw > 0) {
            text.setTextSize(size * maxW / tw);
        }
        c.drawText(s, x, baseline, text);
    }

    private void woodButton(Canvas c, RectF rc, boolean enabled, boolean highlight) {
        float rad = rc.height() * 0.22f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, rc.top, 0, rc.bottom,
                highlight ? new int[] {0xFFB98B3E, 0xFF7A5420} : new int[] {0xFF6E4527, 0xFF3F2514},
                new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        paint.setAlpha(enabled ? 255 : 120);
        c.drawRoundRect(rc, rad, rad, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, rc.height() * 0.025f));
        paint.setColor(0xFFD4AF37);
        paint.setAlpha(enabled ? 190 : 70);
        c.drawRoundRect(rc, rad, rad, paint);
        paint.setAlpha(255);
    }

    // ------------------------------------------------------------------
    // Çizim
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!layoutReady) {
            computeLayout();
            if (!layoutReady) {
                return;
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bgShader);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);

        if (menuOpen) {
            drawBoard(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF0E0906);
            paint.setAlpha(205);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setAlpha(255);
            drawMenu(canvas);
            return;
        }
        drawBoard(canvas);
        drawMarks(canvas);
        drawStacks(canvas);
        drawHints(canvas);
        drawTrays(canvas);
        drawDiceOrRoll(canvas);
        drawLeftPanel(canvas);
        drawRightPanel(canvas);
        drawStatus(canvas);
        if (game.phase == Game.PH_OVER) {
            drawOver(canvas);
        }
    }

    private void drawMenu(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float cx = (insL + w - insR) / 2f;
        int n = menuItems.size();
        int cols = n > 6 ? 2 : 1;
        int rows = (n + cols - 1) / cols;
        float titleH = h * 0.24f;
        float availH = h - titleH - h * 0.06f;
        float gap = Math.min(h * 0.022f, 18f);
        float bh = Math.min(h * 0.13f, (availH - gap * (rows - 1)) / rows);
        float bw = Math.min((w - insL - insR) * (cols == 1 ? 0.5f : 0.4f), bh * (cols == 1 ? 7f : 6f));
        float totalW = bw * cols + gap * (cols - 1);
        float y0 = h * 0.03f + titleH + Math.max(0f, (availH - (bh * rows + gap * (rows - 1))) / 2f);

        // başlık
        RectF tr = new RectF(cx - w * 0.4f, h * 0.035f, cx + w * 0.4f, h * 0.035f + titleH * 0.62f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        centerText(c, menuTitle, shadowRect(tr, h * 0.006f), 0xFF000000, titleH * 0.5f);
        centerText(c, menuTitle, tr, 0xFFE9C46A, titleH * 0.5f);
        RectF sr = new RectF(cx - w * 0.4f, tr.bottom, cx + w * 0.4f, tr.bottom + titleH * 0.26f);
        centerText(c, menuSub, sr, 0xFFC9B58A, titleH * 0.17f);

        menuRects.clear();
        for (int i = 0; i < n; i++) {
            int col = cols == 1 ? 0 : i / rows;
            int row = cols == 1 ? i : i % rows;
            float x = cx - totalW / 2f + col * (bw + gap);
            float y = y0 + row * (bh + gap);
            RectF rc = new RectF(x, y, x + bw, y + bh);
            menuRects.add(rc);
            MenuItem it = menuItems.get(i);
            woodButton(c, rc, true, it.primary);
            centerText(c, it.label, rc, 0xFFFFF4D6, bh * 0.36f);
        }
    }

    private RectF shadowRect(RectF r, float d) {
        return new RectF(r.left + d, r.top + d, r.right + d, r.bottom + d);
    }

    private void drawBoard(Canvas c) {
        // gölge
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setAlpha(110);
        rect.set(bx0 + 6 * u, by0 + 10 * u, bx1 + 6 * u, by1 + 10 * u);
        c.drawRoundRect(rect, geo.fr * 0.9f, geo.fr * 0.9f, paint);
        paint.setAlpha(255);
        if (boardBmp != null) {
            c.drawBitmap(boardBmp, bx0, by0, null);
        }
    }

    private void drawTriangleOverlay(Canvas c, int pt, int color, int alpha) {
        float x = colLeft(colOf(disp(pt)));
        android.graphics.Path t = new android.graphics.Path();
        if (ptTop(pt)) {
            t.moveTo(x + cw * 0.035f, iy0);
            t.lineTo(x + cw * 0.965f, iy0);
            t.lineTo(x + cw * 0.5f, iy0 + triH * 0.94f);
        } else {
            t.moveTo(x + cw * 0.035f, iy1);
            t.lineTo(x + cw * 0.965f, iy1);
            t.lineTo(x + cw * 0.5f, iy1 - triH * 0.94f);
        }
        t.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        c.drawPath(t, paint);
        paint.setAlpha(255);
    }

    private void drawMarks(Canvas c) {
        if (game.lastFrom >= 1 && game.lastFrom <= 24) {
            drawTriangleOverlay(c, game.lastFrom, 0xFFFFFFFF, 34);
        }
        if (game.lastTo >= 1 && game.lastTo <= 24) {
            drawTriangleOverlay(c, game.lastTo, 0xFFFFE08A, 52);
        }
    }

    private void drawStacks(Canvas c) {
        if (whiteBmp == null) {
            return;
        }
        Board b = game.board;
        for (int pt = 1; pt <= 24; pt++) {
            int total = b.cnt[0][pt] + b.cnt[1][pt];
            if (total == 0) {
                continue;
            }
            int pin = b.pinned[pt];
            int[] order = new int[total];
            int n = 0;
            if (pin != Board.NONE) {
                order[n++] = pin;
            }
            for (int p = 0; p < 2; p++) {
                int count = b.cnt[p][pt] - (p == pin ? 1 : 0);
                for (int i = 0; i < count; i++) {
                    order[n++] = p;
                }
            }
            float cx = ptCx(pt);
            float sp = stackSpacing(total);
            for (int k = 0; k < total; k++) {
                float cy = slotY(pt, k, sp);
                Bitmap bm = order[k] == Board.WHITE ? whiteBmp : blackBmp;
                c.drawBitmap(bm, cx - chkSize / 2f, cy - chkSize / 2f, null);
                if (k == 0 && pin != Board.NONE) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(3f, r * 0.16f));
                    paint.setColor(0xFFE53935);
                    c.drawCircle(cx, cy, r * 0.9f, paint);
                }
            }
            if (total > 5) {
                float cy = slotY(pt, total - 1, sp);
                rect.set(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xCC000000);
                c.drawCircle(cx, cy, r * 0.5f, paint);
                centerText(c, String.valueOf(total), rect, 0xFFFFFFFF, r * 0.7f);
            }
        }
    }

    private void drawRing(Canvas c, float cx, float cy, float radius, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawCircle(cx, cy, radius, paint);
    }

    private void drawHints(Canvas c) {
        if (game.phase != Game.PH_MOVE || !game.isHumanTurn()) {
            return;
        }
        Board b = game.board;
        if (game.selected == 0) {
            boolean[] from = new boolean[25];
            for (Board.Move m : game.allowed) {
                from[m.from] = true;
            }
            for (int pt = 1; pt <= 24; pt++) {
                if (from[pt]) {
                    int total = b.cnt[0][pt] + b.cnt[1][pt];
                    float sp = stackSpacing(total);
                    drawRing(c, ptCx(pt), slotY(pt, total - 1, sp), r * 1.06f, 0xAAFFD54F, Math.max(3f, r * 0.12f));
                }
            }
            return;
        }
        int sel = game.selected;
        int total = b.cnt[0][sel] + b.cnt[1][sel];
        float sp = stackSpacing(total);
        drawRing(c, ptCx(sel), slotY(sel, total - 1, sp), r * 1.08f, 0xFFFFD54F, Math.max(4f, r * 0.2f));
        for (Game.Dest d : game.dests) {
            if (d.to == Board.OFF) {
                continue;
            }
            int col = d.pin ? 0xFFFF9800 : (d.combo ? 0xFF29B6F6 : 0xFF66BB6A);
            drawTriangleOverlay(c, d.to, col, 105);
            int t = b.cnt[0][d.to] + b.cnt[1][d.to];
            float cy = slotY(d.to, Math.min(t, 4), 2f * r);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(col);
            paint.setAlpha(235);
            c.drawCircle(ptCx(d.to), cy, r * 0.56f, paint);
            paint.setAlpha(255);
            rect.set(ptCx(d.to) - r * 0.56f, cy - r * 0.56f, ptCx(d.to) + r * 0.56f, cy + r * 0.56f);
            centerText(c, String.valueOf(d.label), rect, 0xFFFFFFFF, r * 0.78f);
        }
    }

    private RectF trayRect(boolean top) {
        float tx0 = ix0 + 12.6f * cw + cw * 0.08f;
        float tx1 = bx1 - geo.fr;
        float mid = (iy0 + iy1) / 2f;
        if (top) {
            rect.set(tx0, iy0, tx1, mid - geo.fr * 0.15f);
        } else {
            rect.set(tx0, mid + geo.fr * 0.15f, tx1, iy1);
        }
        return rect;
    }

    private void drawTrays(Canvas c) {
        int bottomPlayer = game.viewColor();
        for (int i = 0; i < 2; i++) {
            boolean top = i == 0;
            int player = top ? 1 - bottomPlayer : bottomPlayer;
            RectF tr = new RectF(trayRect(top));
            int n = game.board.off[player];
            float chH = Math.min(r * 0.40f, (tr.height() - r * 1.4f) / 15f);
            float chW = tr.width() * 0.78f;
            for (int k = 0; k < n; k++) {
                float y = top ? tr.top + chH * 0.8f + k * chH : tr.bottom - chH * 0.8f - k * chH;
                rect.set(tr.centerX() - chW / 2f, y - chH * 0.44f, tr.centerX() + chW / 2f, y + chH * 0.44f);
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        player == Board.WHITE ? new int[] {0xFFFFFFFF, 0xFFC9BFA6} : new int[] {0xFF666666, 0xFF0C0C0C},
                        new float[] {0f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(rect, chH * 0.3f, chH * 0.3f, paint);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f);
                paint.setColor(player == Board.WHITE ? 0xFF8F8570 : 0xFF000000);
                c.drawRoundRect(rect, chH * 0.3f, chH * 0.3f, paint);
            }
            rect.set(tr.left, top ? tr.bottom - r * 0.9f : tr.top, tr.right, top ? tr.bottom : tr.top + r * 0.9f);
            centerText(c, n + "/15", rect, 0xFFF3E6C8, r * 0.62f);
            if (game.phase == Game.PH_MOVE && game.isHumanTurn() && game.selected != 0 && player == game.turn) {
                for (Game.Dest d : game.dests) {
                    if (d.to == Board.OFF) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(Math.max(3f, cw * 0.05f));
                        paint.setColor(d.combo ? 0xFF29B6F6 : 0xFF66BB6A);
                        c.drawRoundRect(tr, 8f * u, 8f * u, paint);
                    }
                }
            }
        }
    }

    private void drawDie(Canvas c, float x, float y, float s, int v, int owner, boolean used) {
        int light = owner == Board.WHITE ? 0xFFFFFBEF : 0xFF3A3A3A;
        int dark = owner == Board.WHITE ? 0xFFD8D0BC : 0xFF0E0E0E;
        int pip = owner == Board.WHITE ? 0xFF1E1E1E : 0xFFF7F4EA;
        int alpha = used ? 90 : 255;
        rect.set(x, y, x + s, y + s);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(x, y, x + s, y + s, new int[] {light, dark}, new float[] {0f, 1f},
                Shader.TileMode.CLAMP));
        paint.setAlpha(alpha);
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, s * 0.04f));
        paint.setColor(owner == Board.WHITE ? 0xFF8F8570 : 0xFF000000);
        paint.setAlpha(alpha);
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pip);
        paint.setAlpha(alpha);
        float pr = s * 0.085f;
        float[][] pts = PIPS[v];
        for (int i = 0; i < pts.length; i++) {
            c.drawCircle(x + pts[i][0] * s, y + pts[i][1] * s, pr, paint);
        }
        paint.setAlpha(255);
    }

    private float halfCenterX(int player) {
        boolean mine = player == game.viewColor();
        return mine ? ix0 + 9.6f * cw : ix0 + 3f * cw;
    }

    private void drawDiceOrRoll(Canvas c) {
        float cy = (iy0 + iy1) / 2f;
        if (game.canRoll()) {
            float w = cw * 3.6f;
            float h = midH * 0.84f;
            float cx = halfCenterX(game.turn);
            rollBtn.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            woodButton(c, rollBtn, true, true);
            centerText(c, "ZAR AT", rollBtn, 0xFFFFF4D6, h * 0.5f);
            return;
        }
        if (!game.diceShown) {
            return;
        }
        boolean dbl = game.dice[0] == game.dice[1];
        int slots = dbl ? 4 : 2;
        float ds = Math.min(midH * 0.84f, cw * 0.95f);
        float gap = ds * 0.26f;
        float total = slots * ds + (slots - 1) * gap;
        float x = halfCenterX(game.diceOwner) - total / 2f;
        float y = cy - ds / 2f;
        for (int i = 0; i < slots; i++) {
            boolean used;
            if (game.phase == Game.PH_ROLLING) {
                used = false;
            } else if (dbl) {
                used = i >= game.remaining.size();
            } else {
                used = !game.remaining.contains(game.dice[i]);
            }
            drawDie(c, x + i * (ds + gap), y, ds, game.dice[dbl ? 0 : i], game.diceOwner, used);
        }
    }

    // ------------------------------------------------------------------
    // Paneller
    // ------------------------------------------------------------------

    private void drawCard(Canvas c, RectF rc, int player) {
        float rad = rc.height() * 0.07f;
        boolean active = game.phase != Game.PH_OVER && game.turn == player;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, rc.top, 0, rc.bottom, new int[] {0xF0432A19, 0xF0221409},
                new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(rc, rad, rad, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(active ? Math.max(3f, rc.height() * 0.018f) : Math.max(1.5f, rc.height() * 0.008f));
        paint.setColor(active ? 0xFFFFD54F : 0xFF9C7A33);
        c.drawRoundRect(rc, rad, rad, paint);

        float h = rc.height();
        float pad = h * 0.07f;
        float ic = h * 0.17f;
        Bitmap icon = player == Board.WHITE ? whiteBmp : blackBmp;
        if (icon != null) {
            rect.set(rc.left + pad, rc.top + pad, rc.left + pad + 2 * ic, rc.top + pad + 2 * ic);
            c.drawBitmap(icon, null, rect, null);
        }
        float tx = rc.left + pad * 2 + 2 * ic;
        float tw = rc.right - tx - pad;
        leftText(c, game.playerName(player), tx, rc.top + pad + ic * 1.0f, 0xFFFFF4D6, h * 0.12f, tw);
        leftText(c, Game.colorName(player) + (active ? "  •  SIRA" : ""), tx, rc.top + pad + ic * 1.75f,
                active ? 0xFFFFD54F : 0xFFC9B58A, h * 0.075f, tw);

        // puan
        RectF sc = new RectF(rc.left + pad, rc.top + h * 0.36f, rc.left + rc.width() * 0.42f, rc.top + h * 0.78f);
        centerText(c, String.valueOf(game.score[player]), sc, 0xFFFFF4D6, h * 0.30f);
        RectF lb = new RectF(sc.left, rc.top + h * 0.76f, sc.right, rc.top + h * 0.90f);
        centerText(c, "PUAN", lb, 0xFFC9B58A, h * 0.07f);

        // taş ve mesafe
        float cx = rc.left + rc.width() * 0.47f;
        float cwid = rc.right - cx - pad;
        leftText(c, "Toplanan", cx, rc.top + h * 0.50f, 0xFFC9B58A, h * 0.065f, cwid);
        leftText(c, game.board.off[player] + " / 15", cx, rc.top + h * 0.61f, 0xFFFFF4D6, h * 0.10f, cwid);
        leftText(c, "Kalan mesafe", cx, rc.top + h * 0.75f, 0xFFC9B58A, h * 0.065f, cwid);
        leftText(c, String.valueOf(game.board.pips(player)), cx, rc.top + h * 0.87f, 0xFFFFF4D6, h * 0.10f, cwid);
    }

    private String modeLabel() {
        if (game.mode == Game.MODE_AI) {
            String[] n = {"Kolay", "Orta", "Zor"};
            return "Bilgisayar · " + n[Math.max(0, Math.min(2, game.aiLevel))];
        }
        if (game.mode == Game.MODE_TWO) {
            return "Aynı telefonda 2 kişi";
        }
        return "Çevrimiçi oyun";
    }

    private void drawLeftPanel(Canvas c) {
        int bottom = game.viewColor();
        drawCard(c, card0, 1 - bottom);
        drawCard(c, card1, bottom);
        RectF mid = new RectF(leftPanel.left, card0.bottom, leftPanel.right, card1.top);
        centerText(c, modeLabel(), mid, 0xFFC9B58A, mid.height() * 0.28f);
    }

    private void drawRightPanel(Canvas c) {
        String[] labels = {"MENÜ", "GERİ AL", "İPUCU", "KURALLAR"};
        for (int i = 0; i < B_COUNT; i++) {
            boolean enabled = true;
            if (i == B_UNDO) {
                enabled = game.canUndo();
            } else if (i == B_HINT) {
                enabled = game.canHint();
            }
            woodButton(c, btn[i], enabled, false);
            centerText(c, labels[i], btn[i], enabled ? 0xFFFFF4D6 : 0xFF9C8A6A, btn[i].height() * 0.36f);
        }
    }

    private void drawStatus(Canvas c) {
        float rad = statusRect.height() * 0.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setAlpha(150);
        c.drawRoundRect(statusRect, rad, rad, paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0xFF9C7A33);
        c.drawRoundRect(statusRect, rad, rad, paint);
        centerText(c, game.message, statusRect, 0xFFFFF4D6, statusRect.height() * 0.5f);
    }

    private void drawOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setAlpha(150);
        c.drawRoundRect(new RectF(bx0, by0, bx1, by1), geo.fr * 0.8f, geo.fr * 0.8f, paint);
        paint.setAlpha(255);
        float cx = (bx0 + bx1) / 2f;
        float cy = (by0 + by1) / 2f;
        float bw = (bx1 - bx0) * 0.62f;
        float bh = (by1 - by0) * 0.66f;
        RectF card = new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
        paint.setShader(new LinearGradient(0, card.top, 0, card.bottom, new int[] {0xFF4A2F1B, 0xFF24150B},
                new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(card, bh * 0.05f, bh * 0.05f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, bh * 0.008f));
        paint.setColor(0xFFD4AF37);
        c.drawRoundRect(card, bh * 0.05f, bh * 0.05f, paint);

        RectF t = new RectF(card.left, card.top + bh * 0.05f, card.right, card.top + bh * 0.25f);
        centerText(c, game.overTitle, t, 0xFFFFD54F, bh * 0.13f);
        t.set(card.left, card.top + bh * 0.26f, card.right, card.top + bh * 0.38f);
        centerText(c, game.overSub, t, 0xFFFFF4D6, bh * 0.07f);
        if (!game.overExtra.isEmpty()) {
            String[] lines = game.overExtra.split("\n");
            float ly = card.top + bh * 0.42f;
            for (String ln : lines) {
                t.set(card.left, ly, card.right, ly + bh * 0.075f);
                centerText(c, ln, t, 0xFFC9B58A, bh * 0.055f);
                ly += bh * 0.08f;
            }
        }
        boolean online = game.mode == Game.MODE_ONLINE;
        overNew.set(card.left + bw * 0.06f, card.bottom - bh * 0.22f, card.left + bw * 0.52f, card.bottom - bh * 0.06f);
        overMenu.set(card.left + bw * 0.56f, card.bottom - bh * 0.22f, card.right - bw * 0.06f, card.bottom - bh * 0.06f);
        woodButton(c, overNew, !(online && game.myRematch), true);
        centerText(c, online ? (game.myRematch ? "Bekleniyor…" : "Tekrar Oyna") : "Yeni Oyun", overNew, 0xFFFFF4D6,
                overNew.height() * 0.40f);
        woodButton(c, overMenu, true, false);
        centerText(c, "Menü", overMenu, 0xFFFFF4D6, overMenu.height() * 0.40f);
    }
}
