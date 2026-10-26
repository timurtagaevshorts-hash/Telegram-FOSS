package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.YuzWallpaper;

/**
 * YuzGram #193: "Fon" pill tugmasi — APK himoya tugmasining yonida turadi.
 * Rasm tanlangan bo'lsa brend-ko'k, aks holda kulrang. Bosilganda fon rasmi
 * oynasi ([uz.kodlikino.top4.ui.YuzGramWallpaperDialog]) ochiladi.
 */
public class YuzGramWallpaperPill extends View {

    private static final int ON = 0xFF38B6FF;
    private static final int OFF = 0xFF7A8794;
    private static final int ON_TEXT = 0xFFE8F6FF;
    private static final int OFF_TEXT = 0xFFA7B4C2;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path mountain = new Path();
    private final String label;

    private final int height = AndroidUtilities.dp(30);
    private final float iconSize = AndroidUtilities.dp(14);
    private final float padding = AndroidUtilities.dp(9);
    private final float gap = AndroidUtilities.dp(5);

    private boolean active = YuzWallpaper.isActive();
    private int width;

    public YuzGramWallpaperPill(Context context) {
        super(context);
        label = context.getString(R.string.yuz_wallpaper_pill);
        stroke.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        textPaint.setTextSize(AndroidUtilities.dp(12));
        width = Math.round(padding * 2 + iconSize + gap + textPaint.measureText(label));
    }

    public void setActive(boolean value) {
        if (active != value) {
            active = value;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int color = active ? ON : OFF;
        float radius = height / 2f;

        fill.setColor(active ? 0x2E38B6FF : 0x1A7A8794);
        canvas.drawRoundRect(0, 0, width, height, radius, radius, fill);

        stroke.setColor(active ? 0x9938B6FF : 0x667A8794);
        stroke.setStrokeWidth(AndroidUtilities.dp(1));
        float inset = AndroidUtilities.dp(0.5f);
        canvas.drawRoundRect(inset, inset, width - inset, height - inset,
                radius - inset, radius - inset, stroke);

        float iconTop = (height - iconSize) / 2f;
        canvas.save();
        canvas.translate(padding, iconTop);
        canvas.scale(iconSize, iconSize);
        drawGlyph(canvas, active ? ON : color, active);
        canvas.restore();

        textPaint.setColor(active ? ON_TEXT : OFF_TEXT);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = height / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, padding + iconSize + gap, textY, textPaint);
    }

    /** Rasm belgisi: yumaloq ramka + quyosh + tog' chizig'i (0..1 maydonda). */
    private void drawGlyph(Canvas canvas, int color, boolean filled) {
        frame.set(0.06f, 0.12f, 0.94f, 0.88f);
        float r = 0.14f;
        if (filled) {
            fill.setColor(color);
            fill.setAlpha(46);
            canvas.drawRoundRect(frame, r, r, fill);
            fill.setAlpha(255);
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(1.6f / iconSize);
        canvas.drawRoundRect(frame, r, r, stroke);

        fill.setColor(color);
        canvas.drawCircle(0.32f, 0.36f, 0.10f, fill);

        mountain.reset();
        mountain.moveTo(0.16f, 0.76f);
        mountain.lineTo(0.40f, 0.46f);
        mountain.lineTo(0.58f, 0.66f);
        mountain.lineTo(0.70f, 0.55f);
        mountain.lineTo(0.84f, 0.76f);
        stroke.setStrokeWidth(1.6f / iconSize);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(mountain, stroke);
    }
}
