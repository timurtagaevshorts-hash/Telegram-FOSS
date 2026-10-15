package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram pastki navigatsiya bari — LaunchActivity tomonidan doimiy (persistent)
 * usulda boshqariladi. 3 tab: Asosiy / Elonlar / Profil.
 *
 * Xususiyatlari:
 *  - Ikonkalar Canvas bilan chiziladi (resursga bog'liq emas).
 *  - Faol tab ostida silliq siljuvchi "pill" indikator (animatsiyali).
 *  - show()/hide() — translationY + alpha animatsiyasi (chat kabi ichki ekranlarda yashiriladi).
 *  - Mavzu ranglariga moslashadi.
 */
public class YuzGramBottomBar extends View {

    public static final int TAB_ASOSIY = 0;
    public static final int TAB_ELONLAR = 1;
    public static final int TAB_PROFIL = 2;
    public static final int COUNT = 3;

    public static final int HEIGHT_DP = 62;

    private static final String[] LABELS = {"Asosiy", "Elonlar", "Profil"};
    private static final int ACCENT = 0xFF12B7A6; // YuzGram brend to'q ko'k-yashil

    private final Paint bgPaint = new Paint();
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final Path path = new Path();

    private int activeTab = TAB_ASOSIY;
    private float indicatorPos = 0f; // 0..COUNT-1, animatsiyalanadi
    private OnTabSelectedListener listener;
    private boolean shown = false;

    private final float textSize = AndroidUtilities.dp(12);
    private final float iconSize = AndroidUtilities.dp(22);

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
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(AndroidUtilities.dp(0.7f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSize);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(2f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
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
        indicatorAnim.setDuration(280);
        indicatorAnim.setInterpolator(new DecelerateInterpolator(1.6f));
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

    private void applyTheme() {
        bgPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        applyTheme();
        int w = getWidth();
        int h = getHeight();
        float tabW = w / (float) COUNT;

        // fon + tepa divider
        canvas.drawRect(0, 0, w, h, bgPaint);
        float dy = dividerPaint.getStrokeWidth() / 2f;
        canvas.drawLine(0, dy, w, dy, dividerPaint);

        // siljuvchi pill indikator
        float pillW = tabW * 0.82f;
        float cx = (indicatorPos + 0.5f) * tabW;
        pillRect.set(cx - pillW / 2f, AndroidUtilities.dp(6), cx + pillW / 2f, h - AndroidUtilities.dp(6));
        pillPaint.setColor((ACCENT & 0x00FFFFFF) | 0x22000000);
        canvas.drawRoundRect(pillRect, AndroidUtilities.dp(16), AndroidUtilities.dp(16), pillPaint);

        float iconCy = AndroidUtilities.dp(24);
        float textBaseline = h - AndroidUtilities.dp(11);

        for (int i = 0; i < COUNT; i++) {
            float tcx = (i + 0.5f) * tabW;
            boolean active = i == activeTab;
            int color = active ? ACCENT : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            drawIcon(canvas, i, tcx, iconCy, color, active);

            textPaint.setColor(color);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL));
            canvas.drawText(LABELS[i], tcx, textBaseline, textPaint);
        }
    }

    private void drawIcon(Canvas canvas, int tab, float cx, float cy, int color, boolean active) {
        iconPaint.setColor(color);
        textPaint.setColor(color);
        float s = iconSize;
        float half = s / 2f;
        switch (tab) {
            case TAB_ASOSIY: {
                // suhbat ko'pigi: rounded rect + dumi
                float r = AndroidUtilities.dp(5);
                RectF bubble = new RectF(cx - half, cy - half + AndroidUtilities.dp(1), cx + half, cy + half - AndroidUtilities.dp(4));
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
                float headR = AndroidUtilities.dp(4.5f);
                canvas.drawCircle(cx, cy - AndroidUtilities.dp(4), headR, iconPaint);
                path.reset();
                RectF body = new RectF(cx - half, cy + AndroidUtilities.dp(2), cx + half, cy + half + AndroidUtilities.dp(8));
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
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            float tabW = getWidth() / (float) COUNT;
            int index = Math.max(0, Math.min(COUNT - 1, (int) (event.getX() / tabW)));
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
}
