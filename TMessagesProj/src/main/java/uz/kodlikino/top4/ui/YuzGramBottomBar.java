package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram pastki navigatsiya bari — zamonaviy "floating pill" uslubidagi ko'tarma panel.
 *
 * Dizayn:
 *  - Kichraytirilgan balandlik, yon tomonlarda chekinish bilan suzuvchi yumaloq panel.
 *  - Faol tab'ning orqasida silliq siljuvchi gradient "pill" indikator.
 *  - Har bir tab uchun o'ziga xos jonli accent rang (suzuvchi ikon + gradient).
 *  - Ikonkalar Canvas'da chiziladi, matn — kichik va aniq.
 *  - Dark/Light mavzuga avtomatik moslashadi.
 */
public class YuzGramBottomBar extends View {

    public static final int TAB_ASOSIY = 0;
    public static final int TAB_ELONLAR = 1;
    public static final int TAB_PROFIL = 2;
    public static final int COUNT = 3;

    /** Panelning umumiy balandligi (dip) — pastki chekinish bilan. */
    public static final int HEIGHT_DP = 58;

    private static final String[] LABELS = {"Asosiy", "Elonlar", "Profil"};

    // Zamonaviy gradient accent ranglar (start/end) — har tab uchun o'ziga xos.
    private static final int[] GRAD_START = {0xFF5B8CFF, 0xFF22C7A0, 0xFFF56B9E};
    private static final int[] GRAD_END = {0xFF8E6BFF, 0xFF24C1D6, 0xFFFA7B54};
    private static final int INACTIVE_DARK = 0xFF8A93A6;   // dark mavzuda grey ikon
    private static final int INACTIVE_LIGHT = 0xFF9AA0AE;  // light mavzuda grey ikon

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Path path = new Path();

    private int activeTab = TAB_ASOSIY;
    private float indicatorPos = 0f; // 0..COUNT-1, animatsiyalanadi
    private float pressScale = 0f;   // bosilganda pill'ning kichik "bounce" effekti
    private OnTabSelectedListener listener;
    private boolean shown = false;

    private final float textSize = AndroidUtilities.dp(10.5f);
    private final float iconSize = AndroidUtilities.dp(20);

    // Panel geometriyasi (onDraw hisoblagan)
    private float panelTop, panelBottom, panelLeft, panelRight;
    private float segLeft, segRight, cellW, pillW, pillH, pillTop;

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
        borderPaint.setStrokeWidth(AndroidUtilities.dp(0.6f));
        setLayerType(LAYER_TYPE_SOFTWARE, null); // soya (setShadowLayer) uchun
        indicatorPos = activeTab;
    }

    public static int height() {
        return AndroidUtilities.dp(HEIGHT_DP);
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
        indicatorAnim.setDuration(320);
        indicatorAnim.setInterpolator(new OvershootInterpolator(0.9f));
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
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
        } else {
            animate().cancel();
            animate().alpha(0f).translationY(height()).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    public boolean isShownBar() {
        return shown;
    }

    private boolean isDarkTheme() {
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        double lum = (0.299 * (bg >> 16 & 0xFF) + 0.587 * (bg >> 8 & 0xFF) + 0.114 * (bg & 0xFF)) / 255.0;
        return lum < 0.5;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean dark = isDarkTheme();

        // Suuvchi panel: kichik chekinish + katta radius.
        float sideMargin = AndroidUtilities.dp(10);
        float bottomMargin = AndroidUtilities.dp(7);
        float topMargin = AndroidUtilities.dp(4);
        float corner = AndroidUtilities.dp(24);
        panelLeft = sideMargin;
        panelRight = w - sideMargin;
        panelTop = topMargin;
        panelBottom = h - bottomMargin;
        float panelRadius = Math.min(corner, (panelBottom - panelTop) / 2f);

        // Soya (muloyim, chuqurlik)
        if (dark) {
            shadowPaint.setColor(0x66000000);
        } else {
            shadowPaint.setColor(0x22000000);
        }
        shadowPaint.setShadowLayer(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(3),
                dark ? 0x99000000 : 0x33000000);
        canvas.drawRoundRect(panelRect.set(
                panelLeft, panelTop, panelRight, panelBottom), panelRadius, panelRadius, shadowPaint);
        shadowPaint.clearShadowLayer();

        // Panel sirti
        surfacePaint.setColor(dark ? 0xFF1B2530 : 0xFFFFFFFF);
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, surfacePaint);
        borderPaint.setColor(dark ? 0x33FFFFFF : 0x14000000);
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, borderPaint);

        // Gradient pill (siljuvchi indikator)
        segLeft = panelLeft + AndroidUtilities.dp(6);
        segRight = panelRight - AndroidUtilities.dp(6);
        cellW = (segRight - segLeft) / COUNT;
        pillW = cellW - AndroidUtilities.dp(12);
        pillH = panelBottom - panelTop - AndroidUtilities.dp(10);
        pillTop = panelTop + AndroidUtilities.dp(5);
        float pillCx = segLeft + (indicatorPos + 0.5f) * cellW;
        float pillCy = pillTop + pillH / 2f;
        float pillRadius = pillH / 2f;
        float scale = 1f + 0.06f * pressScale;
        float halfW = pillW / 2f * scale;
        float halfH = pillH / 2f * scale;
        pillRect.set(pillCx - halfW, pillCy - halfH, pillCx + halfW, pillCy + halfH);
        int idx = Math.max(0, Math.min(COUNT - 1, activeTab));
        LinearGradient lg = new LinearGradient(
                pillRect.left, pillRect.top, pillRect.right, pillRect.bottom,
                GRAD_START[idx], GRAD_END[idx], Shader.TileMode.CLAMP);
        pillPaint.setShader(lg);
        pillPaint.setAlpha(dark ? 235 : 225);
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillPaint);
        pillPaint.setShader(null);

        float iconCy = pillTop + AndroidUtilities.dp(16);
        float textBaseline = panelBottom - AndroidUtilities.dp(9);

        for (int i = 0; i < COUNT; i++) {
            float tcx = segLeft + (i + 0.5f) * cellW;
            boolean active = i == activeTab;
            int color;
            if (active) {
                color = 0xFFFFFFFF;
            } else {
                color = dark ? INACTIVE_DARK : INACTIVE_LIGHT;
            }
            drawIcon(canvas, i, tcx, iconCy, color, active);

            textPaint.setColor(color);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL));
            textPaint.setAlpha(active ? 255 : (dark ? 200 : 180));
            canvas.drawText(LABELS[i], tcx, textBaseline, textPaint);
        }
    }

    private void drawIcon(Canvas canvas, int tab, float cx, float cy, int color, boolean active) {
        iconPaint.setColor(color);
        float s = iconSize * (active ? 1.06f : 1f);
        float half = s / 2f;
        switch (tab) {
            case TAB_ASOSIY: {
                // suhbat ko'pigi: rounded rect + dumi
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
                // 2x2 to'r (elonlar grid)
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
                // odam: bosh doira + yelka yoyi
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
            // bosilgan tab'ni aniqla va kichik "bounce" ber
            int index = indexAt(event.getX());
            if (index == activeTab) {
                pressScale = 0f;
                ValueAnimator bounce = ValueAnimator.ofFloat(0f, 1f, 0f);
                bounce.setDuration(220);
                bounce.addUpdateListener(a -> {
                    pressScale = (float) a.getAnimatedValue();
                    invalidate();
                });
                bounce.start();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            int index = indexAt(event.getX());
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (index != activeTab) {
                setActiveTab(index);
            }
            if (listener != null) {
                listener.onTabSelected(index);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private int indexAt(float x) {
        float areaLeft = panelLeft != 0 ? segLeft : AndroidUtilities.dp(16);
        float areaW = getWidth() - areaLeft * 2;
        float cw = areaW / COUNT;
        return Math.max(0, Math.min(COUNT - 1, (int) ((x - areaLeft) / cw)));
    }
}
