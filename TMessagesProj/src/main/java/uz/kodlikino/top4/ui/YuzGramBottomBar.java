package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram pastki navigatsiya bari — rasmdagi neon uslubga mos "floating dock".
 *
 * Tuzilishi (4 slot):  Asosiy | [ + markaz FAB ] | Elonlar | Profil
 *  - Panel: yumaloq, to'q-ko'k neon sirt, tashqi ko'k "glow" nur, nozik border.
 *  - Markazda ko'tarilgan dumaloq "+" FAB (gradient binafsha→ko'k), atrofidagi
 *    panelda "cradle" notch (o'yiq) kesilgan.
 *  - Faol tab: yorqin ikon + matn + pastida silliq siljuvchi neon underline.
 *  - "+" hozircha bezak (bosilganda yumshoq bounce, amaliyot keyin belgilanadi).
 *
 * Ikonkalar Canvas'da chiziladi (resursga bog'liq emas), dark neon mavzu doimiy.
 */
public class YuzGramBottomBar extends View {

    public static final int TAB_ASOSIY = 0;
    public static final int TAB_ELONLAR = 1;
    public static final int TAB_PROFIL = 2;
    public static final int COUNT = 3;

    /** Panelning umumiy balandligi (dip) — markaz FAB ko'tarilishi uchun tepa bo'shliq bilan. */
    public static final int HEIGHT_DP = 70;

    private static final String[] LABELS = {"Asosiy", "Elonlar", "Profil"};
    // tab index -> slot index (0..3); slot 1 = markaz FAB
    private static final int[] TAB_SLOT = {0, 2, 3};

    // Neon palitra (YuzGram brendi)
    private static final int SURFACE = 0xFF0A1120;      // chuqur to'q-ko'k sirt (Elonlar home_bg bilan bir xil)
    private static final int BORDER = 0x662AABEE;       // brend-ko'k neon border (#2AABEE)
    private static final int GLOW_BLUE = 0xAA2AABEE;    // tashqi nur (#2AABEE)
    private static final int FAB_START = 0xFF8A5CFF;    // binafsha (home_accent_purple)
    private static final int FAB_END = 0xFF2AABEE;      // brend-ko'k (chat/Profil/Elonlar bilan bir xil)
    private static final int ACTIVE = 0xFF2AABEE;       // faol ikon/matn (brend-ko'k)
    private static final int INACTIVE = 0xFF6B7794;     // o'chiq ikon/matn

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fabGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint underlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private final RectF fabRect = new RectF();
    private final Path path = new Path();

    private int activeTab = TAB_ASOSIY;
    private float indicatorPos = 0f;   // tab-space 0..COUNT-1, animatsiyalanadi
    private float fabPress = 0f;       // "+" bosilganda bounce
    private OnTabSelectedListener listener;
    private boolean shown = false;

    private final float textSize = AndroidUtilities.dp(10.5f);
    private final float iconSize = AndroidUtilities.dp(21);

    // Geometriya (onDraw hisoblaydi)
    private float panelLeft, panelRight, panelTop, panelBottom;
    private float[] slotCx = new float[4];
    private float fabCx, fabCy, fabR;

    public interface OnTabSelectedListener {
        void onTabSelected(int tab);
    }

    public YuzGramBottomBar(Context context) {
        super(context);
        init();
    }

    public YuzGramBottomBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setWillNotDraw(false);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSize);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(1.9f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(AndroidUtilities.dp(1f));
        underlinePaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setLayerType(LAYER_TYPE_SOFTWARE, null); // glow (setShadowLayer) uchun
        indicatorPos = activeTab;
    }

    public static int height() {
        return AndroidUtilities.dp(HEIGHT_DP);
    }

    /**
     * Balandlikni qat'iy dp(HEIGHT_DP)ga qotamiz. LaunchActivity'dagi frameLayout
     * (maxsus FrameLayout) bolaga ba'zan kutilganidan katta balandlik bermoqda —
     * bunda panel (panelBottom = h - dp(8)) butun ekranni egallab, ikonlar bilan
     * matn orasida ulkan bo'sh joy qolardi. EXACTLY spec bu xato holatdan qat'i-nazar
     * barni doim kompakt saqlaydi.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height(), MeasureSpec.EXACTLY));
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    public int getActiveTab() {
        return activeTab;
    }

    public void setActiveTab(int tab) {
        setActiveTab(tab, true);
    }

    public void setActiveTab(int tab, boolean animated) {
        if (tab < 0 || tab >= COUNT) {
            return;
        }
        boolean changed = tab != activeTab;
        activeTab = tab;
        if (changed) {
            animateIndicatorTo(tab, animated);
        }
        invalidate();
    }

    private ValueAnimator indicatorAnim;

    private void animateIndicatorTo(int target, boolean animated) {
        if (indicatorAnim != null) {
            indicatorAnim.cancel();
        }
        if (!animated) {
            indicatorPos = target;
            invalidate();
            return;
        }
        indicatorAnim = ValueAnimator.ofFloat(indicatorPos, target);
        indicatorAnim.setDuration(300);
        indicatorAnim.setInterpolator(new OvershootInterpolator(0.8f));
        indicatorAnim.addUpdateListener(a -> {
            indicatorPos = (float) a.getAnimatedValue();
            invalidate();
        });
        indicatorAnim.start();
    }

    /** Barning ko'rinishini animatsiya bilan yoqish/o'chirish. */
    public void setVisible(boolean visible) {
        if (shown == visible) {
            return;
        }
        shown = visible;
        if (visible) {
            setVisibility(VISIBLE);
            animate().cancel();
            setAlpha(0f);
            setTranslationY(height());
            animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        } else {
            animate().cancel();
            animate().alpha(0f).translationY(height()).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    public boolean isShownBar() {
        return shown;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float sideMargin = AndroidUtilities.dp(10);
        float bottomMargin = AndroidUtilities.dp(8);
        panelLeft = sideMargin;
        panelRight = w - sideMargin;
        panelTop = AndroidUtilities.dp(22);        // tepada FAB ko'tarilishi uchun joy
        panelBottom = h - bottomMargin;
        float corner = AndroidUtilities.dp(24);
        panelRect.set(panelLeft, panelTop, panelRight, panelBottom);

        // 4 slot markazlari
        float usable = panelRight - panelLeft;
        for (int i = 0; i < 4; i++) {
            slotCx[i] = panelLeft + usable * (i + 0.5f) / 4f;
        }
        fabCx = slotCx[1];
        fabR = AndroidUtilities.dp(21);
        fabCy = panelTop;                          // FAB panel tepasiga o'tiradi, yarmi tepada

        // --- Panel sirti + tashqi neon glow + cradle notch ---
        int layer = canvas.saveLayer(
                panelLeft - AndroidUtilities.dp(20), panelTop - AndroidUtilities.dp(20),
                panelRight + AndroidUtilities.dp(20), panelBottom + AndroidUtilities.dp(20), null);

        surfacePaint.setColor(SURFACE);
        surfacePaint.setShadowLayer(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(2), GLOW_BLUE);
        canvas.drawRoundRect(panelRect, corner, corner, surfacePaint);
        surfacePaint.clearShadowLayer();

        // border
        borderPaint.setColor(BORDER);
        canvas.drawRoundRect(panelRect, corner, corner, borderPaint);

        // cradle: FAB atrofida panelni "yeb" qo'yamiz (CLEAR)
        float notchR = fabR + AndroidUtilities.dp(6);
        canvas.drawCircle(fabCx, fabCy, notchR, clearPaint);
        canvas.restoreToCount(layer);

        // --- Markaz FAB ---
        float fabScale = 1f + 0.08f * fabPress;
        float fr = fabR * fabScale;
        // tashqi nur halqasi
        fabGlowPaint.setShader(null);
        fabGlowPaint.setColor(FAB_END);
        fabGlowPaint.setAlpha(90);
        fabGlowPaint.setShadowLayer(AndroidUtilities.dp(18), 0, 0, FAB_START);
        canvas.drawCircle(fabCx, fabCy, fr, fabGlowPaint);
        fabGlowPaint.clearShadowLayer();
        // gradient disk
        fabRect.set(fabCx - fr, fabCy - fr, fabCx + fr, fabCy + fr);
        LinearGradient fabGrad = new LinearGradient(
                fabRect.left, fabRect.top, fabRect.right, fabRect.bottom,
                FAB_START, FAB_END, Shader.TileMode.CLAMP);
        fabPaint.setShader(fabGrad);
        canvas.drawCircle(fabCx, fabCy, fr, fabPaint);
        fabPaint.setShader(null);
        // "+" belgisi
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setColor(0xFFFFFFFF);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(2.6f));
        float plus = AndroidUtilities.dp(8.5f);
        canvas.drawLine(fabCx - plus, fabCy, fabCx + plus, fabCy, iconPaint);
        canvas.drawLine(fabCx, fabCy - plus, fabCx, fabCy + plus, iconPaint);
        iconPaint.setColor(INACTIVE);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(1.9f));

        // --- Faol tab underline (siljuvchi neon) ---
        float activeCx = slotCxForIndicator();
        float underlineY = panelBottom - AndroidUtilities.dp(5);
        float uw = AndroidUtilities.dp(18);
        underlinePaint.setShader(null);
        underlinePaint.setColor(ACTIVE);
        underlinePaint.setShadowLayer(AndroidUtilities.dp(8), 0, 0, ACTIVE);
        canvas.drawRoundRect(new RectF(activeCx - uw, underlineY, activeCx + uw, underlineY + AndroidUtilities.dp(3)),
                AndroidUtilities.dp(2), AndroidUtilities.dp(2), underlinePaint);
        underlinePaint.clearShadowLayer();

        // --- Tab ikonlari + matnlari ---
        float iconCy = panelTop + AndroidUtilities.dp(13);
        float textBaseline = panelBottom - AndroidUtilities.dp(9);
        for (int i = 0; i < COUNT; i++) {
            float tcx = slotCx[TAB_SLOT[i]];
            boolean active = i == activeTab;
            int color = active ? ACTIVE : INACTIVE;
            drawIcon(canvas, i, tcx, iconCy, color, active);
            textPaint.setColor(color);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL));
            canvas.drawText(LABELS[i], tcx, textBaseline, textPaint);
        }
    }

    /** indicatorPos (tab-space) ni slot markaziga aylantiradi (2 va 3 oralig'ida interpolatsiya). */
    private float slotCxForIndicator() {
        // tab 0 -> slot 0 ; tab 1 -> slot 2 ; tab 2 -> slot 3
        // indicatorPos 0..1..2 ni uzluksiz slot x ga chizamiz
        if (indicatorPos <= 1f) {
            float t = indicatorPos;
            return lerp(slotCx[TAB_SLOT[0]], slotCx[TAB_SLOT[1]], t);
        } else {
            float t = indicatorPos - 1f;
            return lerp(slotCx[TAB_SLOT[1]], slotCx[TAB_SLOT[2]], t);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void drawIcon(Canvas canvas, int tab, float cx, float cy, int color, boolean active) {
        iconPaint.setColor(color);
        float s = iconSize * (active ? 1.08f : 1f);
        float half = s / 2f;
        switch (tab) {
            case TAB_ASOSIY: {
                float r = AndroidUtilities.dp(5);
                RectF bubble = new RectF(cx - half, cy - half + AndroidUtilities.dp(1),
                        cx + half, cy + half - AndroidUtilities.dp(4));
                drawRoundRectStroke(canvas, bubble, r);
                path.reset();
                path.moveTo(cx - half + AndroidUtilities.dp(6), bubble.bottom);
                path.lineTo(cx - half + AndroidUtilities.dp(4), bubble.bottom + AndroidUtilities.dp(5));
                path.lineTo(cx - half + AndroidUtilities.dp(11), bubble.bottom);
                path.close();
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
                break;
            }
            case TAB_ELONLAR: {
                float gap = AndroidUtilities.dp(2.5f);
                float cell = (s - gap) / 2f;
                float left = cx - half, top = cy - half;
                for (int gx = 0; gx < 2; gx++) {
                    for (int gy = 0; gy < 2; gy++) {
                        RectF cellRect = new RectF(
                                left + gx * (cell + gap),
                                top + gy * (cell + gap),
                                left + gx * (cell + gap) + cell,
                                top + gy * (cell + gap) + cell);
                        drawRoundRectStroke(canvas, cellRect, AndroidUtilities.dp(3));
                    }
                }
                break;
            }
            case TAB_PROFIL: {
                float headR = AndroidUtilities.dp(4.2f);
                canvas.drawCircle(cx, cy - AndroidUtilities.dp(3.5f), headR, iconPaint);
                path.reset();
                RectF body = new RectF(cx - half, cy + AndroidUtilities.dp(2),
                        cx + half, cy + half + AndroidUtilities.dp(7));
                path.addArc(body, 200, 140);
                canvas.drawPath(path, iconPaint);
                break;
            }
        }
    }

    private void drawRoundRectStroke(Canvas canvas, RectF rect, float r) {
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.drawPath(path, iconPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            super.onTouchEvent(event);
            if (isFabHit(event.getX(), event.getY())) {
                fabPress = 0f;
                ValueAnimator bounce = ValueAnimator.ofFloat(0f, 1f, 0f);
                bounce.setDuration(240);
                bounce.addUpdateListener(a -> {
                    fabPress = (float) a.getAnimatedValue();
                    invalidate();
                });
                bounce.start();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (isFabHit(event.getX(), event.getY())) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                // "+" hozircha bezak — amaliyot keyin belgilanadi.
                return true;
            }
            int tab = tabAt(event.getX());
            if (tab >= 0) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                if (tab != activeTab) {
                    setActiveTab(tab);
                }
                if (listener != null) {
                    listener.onTabSelected(tab);
                }
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean isFabHit(float x, float y) {
        float dx = x - fabCx;
        float dy = y - fabCy;
        return (float) Math.hypot(dx, dy) <= fabR + AndroidUtilities.dp(10);
    }

    /** Bosingan x koordinatani tab indeksiga aylantiradi (markaz FAB sloti -1 qaytaradi). */
    private int tabAt(float x) {
        if (slotCx[0] == 0) return TAB_ASOSIY; // hali chizilmagan
        float usable = panelRight - panelLeft;
        int slot = (int) ((x - panelLeft) / (usable / 4f));
        slot = Math.max(0, Math.min(3, slot));
        if (slot == 1) return -1; // markaz FAB — tab emas
        if (slot == 0) return TAB_ASOSIY;
        if (slot == 2) return TAB_ELONLAR;
        return TAB_PROFIL;
    }
}
