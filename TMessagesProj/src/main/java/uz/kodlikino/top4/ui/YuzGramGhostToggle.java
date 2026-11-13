package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram r63: "sharpa rejim" (ghost) qo'sh tugmasi — APK himoya pill'i
 * yonida turadigan ixcham doira. Yoqilganda chizilgan ko'z ustidan chiziq
 * o'tadi: foydalanuvchi hech kimga onlayn ko'rinmaydi va "yozmoqda…"
 * yubormaydi. Holat [org.telegram.messenger.YuzGhostMode]da saqlanadi.
 */
public class YuzGramGhostToggle extends View {

    public static final int SIZE_DP = 32;

    private static final int DARK_ON = 0xFFE0245E;
    private static final int DARK_OFF_FILL = 0x1AFFFFFF;
    private static final int DARK_OFF_RING = 0x33FFFFFF;
    private static final int DARK_OFF_ICON = 0xFFA7B4C2;
    private static final int DARK_ON_ICON = 0xFFFFFFFF;
    private static final int LIGHT_ON = 0xFFC2185B;
    private static final int LIGHT_OFF_FILL = 0x0D000000;
    private static final int LIGHT_OFF_RING = 0x1F000000;
    private static final int LIGHT_OFF_ICON = 0xFF7C6C73;
    private static final int LIGHT_ON_ICON = 0xFFFFFFFF;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path eyePath = new Path();
    private final RectF circleRect = new RectF();

    private final int size = AndroidUtilities.dp(SIZE_DP);
    private boolean ghostEnabled;
    private int onFill;
    private int offFill;
    private int offRing;
    private int offIcon;
    private int onIcon;

    private final NotificationCenter.NotificationCenterDelegate themeDelegate =
            new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int account, Object... args) {
                    if (id == NotificationCenter.didSetNewTheme) {
                        applyThemeColors();
                        invalidate();
                    }
                }
            };

    public YuzGramGhostToggle(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        applyThemeColors();
    }

    private void applyThemeColors() {
        if (Theme.isCurrentThemeDark()) {
            onFill = DARK_ON;
            offFill = DARK_OFF_FILL;
            offRing = DARK_OFF_RING;
            offIcon = DARK_OFF_ICON;
            onIcon = DARK_ON_ICON;
        } else {
            onFill = LIGHT_ON;
            offFill = LIGHT_OFF_FILL;
            offRing = LIGHT_OFF_RING;
            offIcon = LIGHT_OFF_ICON;
            onIcon = LIGHT_ON_ICON;
        }
    }

    public void setGhostEnabled(boolean value) {
        if (ghostEnabled != value) {
            ghostEnabled = value;
            invalidate();
        }
    }

    public boolean isGhostEnabled() {
        return ghostEnabled;
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
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - AndroidUtilities.dp(1f);

        circleRect.set(cx - r, cy - r, cx + r, cy + r);
        fillPaint.setColor(ghostEnabled ? onFill : offFill);
        canvas.drawCircle(cx, cy, r, fillPaint);
        if (!ghostEnabled) {
            ringPaint.setColor(offRing);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1f));
            canvas.drawCircle(cx, cy, r, ringPaint);
        }

        float w = r * 0.52f;
        glyphPaint.setColor(ghostEnabled ? onIcon : offIcon);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
        eyePath.reset();
        eyePath.moveTo(cx - w, cy);
        eyePath.quadTo(cx, cy - w * 0.78f, cx + w, cy);
        eyePath.quadTo(cx, cy + w * 0.78f, cx - w, cy);
        eyePath.close();
        canvas.drawPath(eyePath, glyphPaint);
        glyphPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, w * 0.30f, glyphPaint);
        if (ghostEnabled) {
            // Chizilgan ko'z — "ko'rinmas" holat.
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeWidth(AndroidUtilities.dp(1.9f));
            canvas.drawLine(cx - w * 0.98f, cy + w * 0.92f,
                    cx + w * 0.98f, cy - w * 0.92f, glyphPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start();
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                if (isPressed() != inside) {
                    setPressed(inside);
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                if (event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight()) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
