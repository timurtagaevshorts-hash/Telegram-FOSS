package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/**
 * YuzGram #85: chat ro'yxati tepasidagi qidiruv satri — maketdagi "Qidirish"
 * pill'i va yonidagi dumaloq filtr tugmasi.
 *
 * Ikkita alohida urish zonasi:
 *  • pill  → Telegram qidiruv maydoni (onSearchClicked)
 *  • doira → papka/filtr sozlamalari (onFilterClicked)
 *
 * Nima uchun alohida View: Telegram'ning o'z qidiruvi action bar'dagi ikonkani
 * bosishni talab qiladi; bu yerda qidiruv doim ko'rinib turadigan maydon.
 */
public class YuzGramSearchBar extends View {

    /** Tasma balandligi: pill (40dp) + tepa/past nafasi. */
    public static final int HEIGHT_DP = 52;
    private static final float PILL_H_DP = 40f;
    private static final float SIDE_DP = 14f;
    private static final float BTN_DP = 40f;
    private static final float GAP_DP = 8f;

    private static final int FILL_TOP = 0xCC1E3352;
    private static final int FILL_BOTTOM = 0xCC14263F;
    private static final int STROKE = 0x4D6FA8E0;
    private static final int STROKE_PRESSED = 0x998FC4F0;
    private static final int HINT_TEXT = 0xFFA9C0D8;
    private static final int LENS = 0xFF7FA6C8;
    private static final int SLIDER = 0xFF9FC4E8;
    private static final int KNOB_FILL = 0xFF0E1B2E;

    private static final int ZONE_NONE = 0;
    private static final int ZONE_SEARCH = 1;
    private static final int ZONE_FILTER = 2;

    public interface Listener {
        void onSearchClicked();

        void onFilterClicked();
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glassRect = new RectF();
    private final String hintText;

    private Listener listener;
    private int pressedZone = ZONE_NONE;
    // Pill va doira bir xil balandlikda (40dp) va markazlashgan — shuning uchun
    // bitta vertikal gradient ikkisiga ham yetadi (onDraw'da yaratilmasin).
    private LinearGradient glassShader;

    public YuzGramSearchBar(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        hintText = context.getString(R.string.yuz_chat_search_hint);
        setContentDescription(hintText);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1f));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.7f));
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextSize(dp(14f));
        textPaint.setColor(HINT_TEXT);
    }

    public static int height() {
        return AndroidUtilities.dp(HEIGHT_DP);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : height();
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float half = dp(PILL_H_DP) / 2f;
        float cy = h / 2f;
        glassShader = w > 0 && h > 0
                ? new LinearGradient(0, cy - half, 0, cy + half,
                FILL_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP)
                : null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float cy = h / 2f;
        float pillH = dp(PILL_H_DP);
        float btnR = dp(BTN_DP) / 2f;
        float btnCx = w - dp(SIDE_DP) - btnR;

        glassRect.set(dp(SIDE_DP), cy - pillH / 2f, btnCx - btnR - dp(GAP_DP), cy + pillH / 2f);
        drawGlass(canvas, glassRect, pillH / 2f, glassShader, pressedZone == ZONE_SEARCH);
        glassRect.set(btnCx - btnR, cy - btnR, btnCx + btnR, cy + btnR);
        drawGlass(canvas, glassRect, btnR, glassShader, pressedZone == ZONE_FILTER);

        float lensR = dp(6.4f);
        float lensCx = dp(SIDE_DP) + dp(15f) + lensR;
        drawLens(canvas, lensCx, cy, lensR);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        textPaint.setAlpha(pressedZone == ZONE_SEARCH ? 255 : 236);
        canvas.drawText(hintText, lensCx + lensR + dp(9f),
                cy - (fm.ascent + fm.descent) / 2f, textPaint);
        textPaint.setAlpha(255);

        drawSliders(canvas, btnCx, cy, btnR);
    }

    private void drawLens(Canvas canvas, float cx, float cy, float r) {
        glyphPaint.setColor(LENS);
        canvas.drawCircle(cx, cy, r, glyphPaint);
        float d = r * 0.707f;
        canvas.drawLine(cx + d, cy + d, cx + r + dp(4.6f), cy + r + dp(4.6f), glyphPaint);
    }

    private void drawGlass(Canvas canvas, RectF rect, float radius, Shader shader, boolean pressed) {
        fillPaint.setShader(shader);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        fillPaint.setShader(null);
        if (pressed) {
            fillPaint.setColor(0x1FFFFFFF);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            fillPaint.setColor(0);
        }
        strokePaint.setColor(pressed ? STROKE_PRESSED : STROKE);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    /** Uch gorizontal yo'l + har birida turli joydagi tutqich — "filtr" glyfi. */
    private void drawSliders(Canvas canvas, float cx, float cy, float r) {
        glyphPaint.setColor(SLIDER);
        float half = r * 0.46f;
        float step = r * 0.34f;
        float[] knobAt = {-0.55f, 0.65f, -0.1f};
        for (int i = 0; i < 3; i++) {
            float y = cy - step + i * step;
            canvas.drawLine(cx - half, y, cx + half, y, glyphPaint);
            float kx = cx + half * knobAt[i];
            fillPaint.setColor(KNOB_FILL);
            canvas.drawCircle(kx, y, r * 0.15f, fillPaint);
            canvas.drawCircle(kx, y, r * 0.15f, glyphPaint);
        }
    }

    private int zoneAt(float x, float y) {
        float cy = getHeight() / 2f;
        float btnR = dp(BTN_DP) / 2f;
        float btnCx = getWidth() - dp(SIDE_DP) - btnR;
        float dx = x - btnCx, dy = y - cy;
        if (dx * dx + dy * dy <= (btnR + dp(5f)) * (btnR + dp(5f))) {
            return ZONE_FILTER;
        }
        float pillH = dp(PILL_H_DP);
        if (x >= dp(SIDE_DP) - dp(4f) && x <= btnCx - btnR - dp(GAP_DP) + dp(4f)
                && y >= cy - pillH / 2f - dp(6f) && y <= cy + pillH / 2f + dp(6f)) {
            return ZONE_SEARCH;
        }
        return ZONE_NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int z = zoneAt(event.getX(), event.getY());
                if (z == ZONE_NONE) {
                    return false;
                }
                pressedZone = z;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int z = zoneAt(event.getX(), event.getY());
                if (z != ZONE_NONE && z != pressedZone) {
                    pressedZone = z;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int z = zoneAt(event.getX(), event.getY());
                boolean fire = pressedZone != ZONE_NONE && z == pressedZone;
                pressedZone = ZONE_NONE;
                invalidate();
                if (fire && listener != null) {
                    if (z == ZONE_FILTER) {
                        listener.onFilterClicked();
                    } else {
                        listener.onSearchClicked();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressedZone = ZONE_NONE;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private float dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
