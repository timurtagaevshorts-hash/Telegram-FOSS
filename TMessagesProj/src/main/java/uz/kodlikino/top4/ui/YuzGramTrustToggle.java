package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * Ishonchli kuzatuv tugmasi — APK himoya pill'i va sharpa rejim
 * tugmasining yonidagi ixcham doira (r64, jilo r71).
 *
 * Holatlar:
 *  - bo'sh — kulrang kontur, qalqon konturi;
 *  - faol (ishonchli odamlar bor) — to'ldirilgan qalqon;
 *  - kutilayotgan so'rov bor — o'ng yuqorida qizil nuqta va pulsatsiya halqasi.
 */
public class YuzGramTrustToggle extends View {

    public static final int SIZE_DP = 32;

    private static final int ACTIVE_FILL = 0xFFC2185B;
    private static final int ACTIVE_RING = 0x4DFFFFFF;
    private static final int BADGE_FILL = 0xFFFF3B30;

    private static final int DARK_OFF_FILL = 0x1AFFFFFF;
    private static final int DARK_OFF_RING = 0x33FFFFFF;
    private static final int DARK_OFF_ICON = 0xFFA7B4C2;
    private static final int LIGHT_OFF_FILL = 0x0D000000;
    private static final int LIGHT_OFF_RING = 0x1F000000;
    private static final int LIGHT_OFF_ICON = 0xFF7C6C73;

    private static final long PULSE_PERIOD = 1500L;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shieldPath = new Path();
    private final Path checkPath = new Path();

    private final int size = AndroidUtilities.dp(SIZE_DP);
    private final int badgeSize = AndroidUtilities.dp(9);

    private boolean active;
    private int pending;
    private int offFill;
    private int offRing;
    private int offIcon;
    private float pulse;
    private ValueAnimator pulseAnimator;

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

    public YuzGramTrustToggle(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(org.telegram.messenger.R.string.yuz_trust_toggle));
        ringPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(AndroidUtilities.dp(2f));
        haloPaint.setColor(BADGE_FILL);
        applyThemeColors();
    }

    private void applyThemeColors() {
        if (Theme.isCurrentThemeDark()) {
            offFill = DARK_OFF_FILL;
            offRing = DARK_OFF_RING;
            offIcon = DARK_OFF_ICON;
        } else {
            offFill = LIGHT_OFF_FILL;
            offRing = LIGHT_OFF_RING;
            offIcon = LIGHT_OFF_ICON;
        }
    }

    public void setState(boolean isActive, int pendingCount) {
        boolean changed = active != isActive || pending != pendingCount;
        active = isActive;
        pending = Math.max(0, pendingCount);
        syncPulse();
        if (changed) {
            invalidate();
        }
    }

    public boolean isTrustActive() {
        return active;
    }

    public int getPendingCount() {
        return pending;
    }

    /** Kutilayotgan so'rov bo'lsa yumshoq halqa pulsatsiyasi ishlaydi. */
    private void syncPulse() {
        if (pending > 0 && isAttachedToWindow()) {
            if (pulseAnimator == null) {
                pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
                pulseAnimator.setDuration(PULSE_PERIOD);
                pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                pulseAnimator.setInterpolator(new LinearInterpolator());
                pulseAnimator.addUpdateListener(animation -> {
                    pulse = (float) animation.getAnimatedValue();
                    invalidate();
                });
            }
            if (!pulseAnimator.isStarted()) {
                pulseAnimator.start();
            }
        } else {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
            }
            pulse = 0f;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance()
                .addObserver(themeDelegate, NotificationCenter.didSetNewTheme);
        syncPulse();
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance()
                .removeObserver(themeDelegate, NotificationCenter.didSetNewTheme);
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
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

        // Kutilayotgan so'rov: tashqi halqa sekin kengayib so'nadi.
        if (pending > 0) {
            haloPaint.setAlpha((int) (110 * (1f - pulse)));
            canvas.drawCircle(cx, cy, r + AndroidUtilities.dp(1.5f + 3.5f * pulse), haloPaint);
        }

        RectF circle = new RectF(cx - r, cy - r, cx + r, cy + r);
        fillPaint.setColor(active ? ACTIVE_FILL : offFill);
        canvas.drawCircle(cx, cy, r, fillPaint);
        if (active) {
            ringPaint.setColor(ACTIVE_RING);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1f));
            canvas.drawCircle(cx, cy, r - AndroidUtilities.dp(1.5f), ringPaint);
        } else {
            ringPaint.setColor(offRing);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1f));
            canvas.drawCircle(cx, cy, r, ringPaint);
        }

        int iconColor = active ? 0xFFFFFFFF : offIcon;
        glyphPaint.setColor(iconColor);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(AndroidUtilities.dp(1.5f));

        // Qalqon konturi.
        float w = r * 0.46f;
        float top = cy - w * 1.05f;
        shieldPath.reset();
        shieldPath.moveTo(cx, top);
        shieldPath.lineTo(cx + w, top + w * 0.42f);
        shieldPath.lineTo(cx + w, cy + w * 0.18f);
        shieldPath.quadTo(cx + w, cy + w * 0.92f, cx, cy + w * 1.15f);
        shieldPath.quadTo(cx - w, cy + w * 0.92f, cx - w, cy + w * 0.18f);
        shieldPath.lineTo(cx - w, top + w * 0.42f);
        shieldPath.close();
        canvas.drawPath(shieldPath, glyphPaint);

        // Ichidagi tasdiq belgisi.
        checkPath.reset();
        checkPath.moveTo(cx - w * 0.40f, cy + w * 0.02f);
        checkPath.lineTo(cx - w * 0.08f, cy + w * 0.34f);
        checkPath.lineTo(cx + w * 0.44f, cy - w * 0.30f);
        canvas.drawPath(checkPath, glyphPaint);

        if (pending > 0) {
            float bx = getWidth() - badgeSize / 2f - AndroidUtilities.dp(1f);
            float by = badgeSize / 2f + AndroidUtilities.dp(1f);
            badgePaint.setColor(Theme.isCurrentThemeDark() ? 0xFF1A0910 : 0xFFFFFFFF);
            badgePaint.setStyle(Paint.Style.STROKE);
            badgePaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
            canvas.drawCircle(bx, by, badgeSize / 2f, badgePaint);
            badgePaint.setStyle(Paint.Style.FILL);
            badgePaint.setColor(BADGE_FILL);
            canvas.drawCircle(bx, by, badgeSize / 2f - AndroidUtilities.dp(0.6f), badgePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start();
                return true;
            case MotionEvent.ACTION_MOVE: {
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                if (isPressed() != inside) {
                    setPressed(inside);
                }
                return true;
            }
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