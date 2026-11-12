package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.YuzApkGuard;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram #100 / #178: "APK himoya" pill tugmasi — chat tepasida, brend
 * palavrasi yonida. Qalqon + yozuv: ko‘k = himoya YOQILGAN, kulrang = O‘CHIQ.
 * Bosish holatni [org.telegram.ui.DialogsActivity]da dialog orqali boshqaradi.
 */
public class YuzGramApkToggle extends View {

    private static final int ON = 0xFF9738FF;
    private static final int OFF = 0xFF7A8794;
    private static final int ON_TEXT = 0xFFF3E8FF;
    private static final int OFF_TEXT = 0xFFA7B4C2;

    // Kunduzgi mavzu: oq-ko'k pill, to'q matn — oq fonda ko'rinadi.
    private static final int ON_LIGHT = 0xFF852AEE;
    private static final int OFF_LIGHT = 0xFF7E8A99;
    private static final int ON_TEXT_LIGHT = 0xFF1E242E;
    private static final int OFF_TEXT_LIGHT = 0xFF7E8A99;

    private int iconColor;
    private int offIconColor;
    private int textColor;
    private int offTextColor;
    private int fillColor;
    private int offFillColor;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shield = new Path();
    private final String label;

    private final int height = AndroidUtilities.dp(30);
    private final float iconSize = AndroidUtilities.dp(14);
    private final float padding = AndroidUtilities.dp(9);
    private final float gap = AndroidUtilities.dp(5);

    private boolean enabled = YuzApkGuard.isEnabled();
    private int width;

    public YuzGramApkToggle(Context context) {
        super(context);
        label = context.getString(R.string.yuz_apk_pill);
        stroke.setStyle(Paint.Style.STROKE);
        check.setStyle(Paint.Style.STROKE);
        check.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        textPaint.setTextSize(AndroidUtilities.dp(12));
        buildShield();
        applyThemeColors();
        width = Math.round(padding * 2 + iconSize + gap + textPaint.measureText(label));
    }

    private void applyThemeColors() {
        if (Theme.isCurrentThemeDark()) {
            iconColor = ON;
            offIconColor = OFF;
            textColor = ON_TEXT;
            offTextColor = OFF_TEXT;
            fillColor = 0x2E9738FF;
            offFillColor = 0x1A7A8794;
        } else {
            iconColor = ON_LIGHT;
            offIconColor = OFF_LIGHT;
            textColor = ON_TEXT_LIGHT;
            offTextColor = OFF_TEXT_LIGHT;
            fillColor = 0x1F852AEE;
            offFillColor = 0x0D000000;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
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
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int color = enabled ? iconColor : offIconColor;
        float radius = height / 2f;

        fill.setColor(enabled ? fillColor : offFillColor);
        canvas.drawRoundRect(0, 0, width, height, radius, radius, fill);

        stroke.setColor(withAlpha(color, enabled ? 0x99 : 0x66));
        stroke.setStrokeWidth(AndroidUtilities.dp(1));
        float inset = AndroidUtilities.dp(0.5f);
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius - inset, radius - inset, stroke);

        float iconTop = (height - iconSize) / 2f;
        float scale = iconSize * 0.92f;
        canvas.save();
        canvas.translate(padding + iconSize * 0.04f, iconTop + iconSize * 0.02f);
        canvas.scale(scale, scale);
        if (enabled) {
            fill.setColor(iconColor);
            canvas.drawPath(shield, fill);
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(AndroidUtilities.dp(1.4f) / scale);
        canvas.drawPath(shield, stroke);
        if (enabled) {
            check.setColor(0xFF13061F);
            check.setStrokeWidth(AndroidUtilities.dp(2.6f) / scale);
            canvas.drawLine(0.3f, 0.52f, 0.45f, 0.67f, check);
            canvas.drawLine(0.45f, 0.67f, 0.72f, 0.36f, check);
        }
        canvas.restore();

        textPaint.setColor(enabled ? textColor : offTextColor);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = height / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, padding + iconSize + gap, textY, textPaint);
    }
}
