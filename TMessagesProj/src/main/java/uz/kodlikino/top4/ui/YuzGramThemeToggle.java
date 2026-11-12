package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram r58: asosiy sahifa sarlavhasidagi kun/tun almashtirgich — qidiruv
 * pill'i qatorining o'ng chekkasida turadigan kichik shisha doira.
 *
 * Yorug' (tun) mavzuda quyosh, kunduzgi mavzuda yarim oy chiziladi — belgi
 * bosilganda qaysi rejimga o'tishini ko'rsatadi. Bosish mantig'i
 * [org.telegram.ui.DialogsActivity]da (themeconfig juftligi + needSetDayNightTheme).
 */
public class YuzGramThemeToggle extends View {

    public static final int SIZE_DP = 40;

    private static final int PANEL_CENTER_DARK = 0xFF1B3556;
    private static final int PANEL_EDGE_DARK = 0xFF08111D;
    private static final int RING_DARK = 0x662AABEE;
    private static final int GLOW_DARK = 0x2E38B6FF;
    private static final int PRESS_DARK = 0x26FFFFFF;

    private static final int PANEL_CENTER_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_EDGE_LIGHT = 0xFFDCE9F8;
    private static final int RING_LIGHT = 0x335B6CF9;
    private static final int GLOW_LIGHT = 0x262AABEE;
    private static final int PRESS_LIGHT = 0x14000000;

    /** Quyosh — iliq oltin; yarim oy — sovuq kumush-ko'k. */
    private static final int SUN_CORE_TOP = 0xFFFFE6A8;
    private static final int SUN_CORE_BOTTOM = 0xFFFFA63D;
    private static final int SUN_RAY = 0xFFFFC86B;
    private static final int MOON_DARK = 0xFFD6E8FF;
    private static final int MOON_LIGHT = 0xFF3D5478;

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private final RectF moonOuter = new RectF();
    private final RectF moonCut = new RectF();
    private final Path moonPath = new Path();
    private final Path moonCutPath = new Path();
    private final Path starPath = new Path();

    private final int size = AndroidUtilities.dp(SIZE_DP);
    private final float iconR = AndroidUtilities.dp(7.4f);

    private final NotificationCenter.NotificationCenterDelegate themeDelegate =
            new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int account, Object... args) {
                    if (id == NotificationCenter.didSetNewTheme) {
                        invalidate();
                    }
                }
            };

    public YuzGramThemeToggle(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dp(1.2f));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(AndroidUtilities.dp(1.9f));
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance()
                .addObserver(themeDelegate, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance()
                .removeObserver(themeDelegate, NotificationCenter.didSetNewTheme);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean dark = Theme.isCurrentThemeDark();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - AndroidUtilities.dp(0.5f);

        // Tashqi yumshoq nur — doira "suzib turadi", tekis nishon emas.
        glowPaint.setShader(new RadialGradient(cx, cy, r * 1.45f,
                dark ? GLOW_DARK : GLOW_LIGHT, 0, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 1.45f, glowPaint);
        glowPaint.setShader(null);

        panelPaint.setShader(new RadialGradient(cx, cy - r * 0.35f, r * 1.55f,
                dark ? PANEL_CENTER_DARK : PANEL_CENTER_LIGHT,
                dark ? PANEL_EDGE_DARK : PANEL_EDGE_LIGHT, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, panelPaint);
        panelPaint.setShader(null);

        ringPaint.setColor(dark ? RING_DARK : RING_LIGHT);
        canvas.drawCircle(cx, cy, r, ringPaint);

        if (isPressed()) {
            pressPaint.setColor(dark ? PRESS_DARK : PRESS_LIGHT);
            canvas.drawCircle(cx, cy, r, pressPaint);
        }

        if (dark) {
            drawSun(canvas, cx, cy);
        } else {
            drawMoon(canvas, cx, cy, r);
        }
    }

    /** Quyosh: iliq gradient yadro + sakkiz nur — "kunduzgi rejimga o'tish". */
    private void drawSun(Canvas canvas, float cx, float cy) {
        float coreR = iconR * 0.62f;
        corePaint.setShader(new RadialGradient(cx, cy - coreR * 0.5f, coreR * 1.6f,
                SUN_CORE_TOP, SUN_CORE_BOTTOM, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, corePaint);
        corePaint.setShader(null);

        glyphPaint.setColor(SUN_RAY);
        float inner = iconR * 0.86f;
        float outer = iconR * 1.28f;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            canvas.drawLine(cx + cos * inner, cy + sin * inner,
                    cx + cos * outer, cy + sin * outer, glyphPaint);
        }
    }

    /** Yarim oy + ikki yulduzcha — "tungi rejimga o'tish". */
    private void drawMoon(Canvas canvas, float cx, float cy, float r) {
        float radius = iconR * 0.92f;
        moonOuter.set(cx - radius, cy - radius, cx + radius, cy + radius);
        moonCut.set(cx - radius * 0.30f, cy - radius * 1.34f,
                cx + radius * 1.70f, cy + radius * 0.66f);
        moonPath.reset();
        moonPath.addOval(moonOuter, Path.Direction.CW);
        moonCutPath.reset();
        moonCutPath.addOval(moonCut, Path.Direction.CW);
        moonPath.op(moonCutPath, Path.Op.DIFFERENCE);

        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(Theme.isCurrentThemeDark() ? MOON_DARK : MOON_LIGHT);
        canvas.drawPath(moonPath, glyphPaint);
        glyphPaint.setStyle(Paint.Style.STROKE);

        float starR = AndroidUtilities.dp(1.7f);
        glyphPaint.setColor(Theme.isCurrentThemeDark() ? SUN_RAY : MOON_LIGHT);
        drawStar(canvas, cx + r * 0.44f, cy - r * 0.50f, starR);
        drawStar(canvas, cx + r * 0.30f, cy + r * 0.52f, starR * 0.72f);
    }

    /** To'rt qirrali yulduzcha — to'g'ri chiziqlar nuri. */
    private void drawStar(Canvas canvas, float cx, float cy, float radius) {
        starPath.reset();
        starPath.moveTo(cx, cy - radius);
        starPath.lineTo(cx + radius * 0.28f, cy - radius * 0.28f);
        starPath.lineTo(cx + radius, cy);
        starPath.lineTo(cx + radius * 0.28f, cy + radius * 0.28f);
        starPath.lineTo(cx, cy + radius);
        starPath.lineTo(cx - radius * 0.28f, cy + radius * 0.28f);
        starPath.lineTo(cx - radius, cy);
        starPath.lineTo(cx - radius * 0.28f, cy - radius * 0.28f);
        starPath.close();
        glyphPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(starPath, glyphPaint);
        glyphPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().scaleX(0.90f).scaleY(0.90f).setDuration(90).start();
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                if (isPressed() != inside) {
                    setPressed(inside);
                }
                return true;
            case MotionEvent.ACTION_UP:
                animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                setPressed(false);
                if (event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight()) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                setPressed(false);
                return true;
        }
        return super.onTouchEvent(event);
    }
}
