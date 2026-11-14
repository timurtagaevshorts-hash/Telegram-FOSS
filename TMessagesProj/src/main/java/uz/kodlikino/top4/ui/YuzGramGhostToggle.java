package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram r65: "sharpa rejim" (ghost) tugmasi — APK himoya pill'i yonidagi
 * ixcham doira. Belgisi Snapchat uslubidagi sharpa silueti.
 *
 * Yoqilganda doira brend rangi bilan yonadi (oq sharpa + tashqi yog'du) va
 * bir marta pulse animatsiyasi o'ynaydi; o'chirilganda kulrang konturga
 * qaytadi. Holat [org.telegram.messenger.YuzGhostMode]da saqlanadi.
 */
public class YuzGramGhostToggle extends View {

    public static final int SIZE_DP = 32;

    private static final int DARK_ON = 0xFFE0245E;
    private static final int DARK_ON_GLOW = 0x66E0245E;
    private static final int DARK_OFF_FILL = 0x1AFFFFFF;
    private static final int DARK_OFF_RING = 0x33FFFFFF;
    private static final int DARK_OFF_ICON = 0xFFA7B4C2;
    private static final int LIGHT_ON = 0xFFC2185B;
    private static final int LIGHT_ON_GLOW = 0x55C2185B;
    private static final int LIGHT_OFF_FILL = 0x0D000000;
    private static final int LIGHT_OFF_RING = 0x1F000000;
    private static final int LIGHT_OFF_ICON = 0xFF7C6C73;
    private static final int ON_ICON = 0xFFFFFFFF;

    private static final long PULSE_MS = 360L;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ghostPath = new Path();
    private final RectF domeRect = new RectF();

    private final int size = AndroidUtilities.dp(SIZE_DP);
    private boolean ghostEnabled;
    private float pulse = 1f;
    private ValueAnimator pulseAnim;
    private int onFill;
    private int onGlow;
    private int offFill;
    private int offRing;
    private int offIcon;

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
        glowPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStyle(Paint.Style.FILL);
        applyThemeColors();
    }

    private void applyThemeColors() {
        if (Theme.isCurrentThemeDark()) {
            onFill = DARK_ON;
            onGlow = DARK_ON_GLOW;
            offFill = DARK_OFF_FILL;
            offRing = DARK_OFF_RING;
            offIcon = DARK_OFF_ICON;
        } else {
            onFill = LIGHT_ON;
            onGlow = LIGHT_ON_GLOW;
            offFill = LIGHT_OFF_FILL;
            offRing = LIGHT_OFF_RING;
            offIcon = LIGHT_OFF_ICON;
        }
    }

    public void setGhostEnabled(boolean value) {
        if (ghostEnabled != value) {
            ghostEnabled = value;
            if (value) {
                startPulse();
            } else {
                cancelPulse();
                pulse = 1f;
            }
            invalidate();
        }
    }

    public boolean isGhostEnabled() {
        return ghostEnabled;
    }

    private void startPulse() {
        cancelPulse();
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(PULSE_MS);
        pulseAnim.setInterpolator(new DecelerateInterpolator());
        pulseAnim.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.start();
    }

    private void cancelPulse() {
        if (pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
        }
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
        cancelPulse();
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

        // Yonish paytida kengayib so'nadigan halqa.
        if (ghostEnabled && pulse < 1f) {
            float pr = r * (0.78f + 0.20f * pulse);
            int alpha = (int) (0x66 * (1f - pulse));
            glowPaint.setColor((onGlow & 0x00FFFFFF) | (alpha << 24));
            glowPaint.setStrokeWidth(AndroidUtilities.dp(2.2f) * (1f - pulse * 0.55f));
            canvas.drawCircle(cx, cy, pr, glowPaint);
        }

        fillPaint.setColor(ghostEnabled ? onFill : offFill);
        canvas.drawCircle(cx, cy, r, fillPaint);

        if (ghostEnabled) {
            glowPaint.setColor(onGlow);
            glowPaint.setStrokeWidth(AndroidUtilities.dp(1.4f));
            canvas.drawCircle(cx, cy, r - AndroidUtilities.dp(1.4f), glowPaint);
        } else {
            ringPaint.setColor(offRing);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1f));
            canvas.drawCircle(cx, cy, r, ringPaint);
        }

        buildGhost(cx, cy, r * 0.60f);
        glyphPaint.setColor(ghostEnabled ? ON_ICON : offIcon);
        glyphPaint.setAlpha(ghostEnabled ? 255 : 220);
        canvas.drawPath(ghostPath, glyphPaint);
    }

    /** Snapchat uslubidagi sharpa silueti: gumbaz + uch qirrali etak. */
    private void buildGhost(float cx, float cy, float s) {
        float top = cy - s * 1.16f;
        float side = top + s * 0.98f;
        float mid = cy + s * 0.60f;
        float hem = cy + s * 0.98f;

        ghostPath.reset();
        ghostPath.moveTo(cx - s, mid);
        ghostPath.lineTo(cx - s, side);
        domeRect.set(cx - s, top, cx + s, top + s * 1.96f);
        ghostPath.arcTo(domeRect, 180f, 180f, false);
        ghostPath.lineTo(cx + s, mid);
        ghostPath.quadTo(cx + s * 0.64f, hem, cx + s * 0.28f, mid);
        ghostPath.quadTo(cx - s * 0.08f, hem, cx - s * 0.44f, mid);
        ghostPath.quadTo(cx - s * 0.76f, hem - s * 0.10f, cx - s, mid);
        ghostPath.close();
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
