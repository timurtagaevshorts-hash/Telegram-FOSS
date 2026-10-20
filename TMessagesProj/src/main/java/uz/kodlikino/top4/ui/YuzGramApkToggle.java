package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.YuzApkGuard;

/**
 * YuzGram #100: "APK himoyasi" tugmasi — chat tepasida, brend palavrasi
 * yonida. Qalqon yoniq ko'k = himoya YOQILGAN; kulreng = O'CHIQ.
 * Bosish holatni [DialogsActivity]da dialog orqali boshqaradi.
 */
public class YuzGramApkToggle extends View {

    private static final int ON = 0xFF38B6FF;
    private static final int OFF = 0xFF6B7A8C;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shield = new Path();
    private boolean enabled = YuzApkGuard.isEnabled();

    private final int size = AndroidUtilities.dp(30);

    public YuzGramApkToggle(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(AndroidUtilities.dp(1f));
        check.setStyle(Paint.Style.STROKE);
        check.setStrokeCap(Paint.Cap.ROUND);
        check.setStrokeWidth(AndroidUtilities.dp(2f));
        buildShield();
    }

    /** Material "security" yo'li (24x24 katak) 0..1 kvadratiga normalelangan. */
    private void buildShield() {
        shield.reset();
        shield.moveTo(0.5f, 0.02f);
        shield.lineTo(0.92f, 0.18f);
        shield.lineTo(0.92f, 0.5f);
        shield.cubicTo(0.92f, 0.78f, 0.74f, 0.95f, 0.5f, 1.03f);
        shield.cubicTo(0.26f, 0.95f, 0.08f, 0.78f, 0.08f, 0.5f);
        shield.lineTo(0.08f, 0.18f);
        shield.close();
    }

    public void setEnabled(boolean value) {
        if (enabled != value) {
            enabled = value;
            invalidate();
        }
    }

    public boolean isGuardEnabled() {
        return enabled;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int c = enabled ? ON : OFF;
        float pad = AndroidUtilities.dp(3);
        float w = size - pad * 2;
        // yumshoq doira fon
        fill.setColor((c & 0x00FFFFFF) | (enabled ? 0x33000000 : 0x1A000000));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - AndroidUtilities.dp(0.5f), fill);
        float sx = pad, sy = pad + w * 0.02f, scale = w;
        canvas.save();
        canvas.translate(sx, sy);
        canvas.scale(scale, scale);
        fill.setColor(enabled ? 0xFF0A1120 : 0);
        if (enabled) {
            canvas.drawPath(shield, fill);
        }
        stroke.setColor(c);
        stroke.setStrokeWidth(AndroidUtilities.dp(1.4f) / scale);
        canvas.drawPath(shield, stroke);
        if (enabled) {
            check.setColor(0xFFFFFFFF);
            check.setStrokeWidth(AndroidUtilities.dp(2.6f) / scale);
            canvas.drawLine(0.3f, 0.52f, 0.45f, 0.67f, check);
            canvas.drawLine(0.45f, 0.67f, 0.72f, 0.36f, check);
        }
        canvas.restore();
    }
}
