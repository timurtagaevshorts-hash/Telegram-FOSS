package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/**
 * YuzGram #60: chat tab tepasidagi brend belgisi — oddiy "YuzGram" matnli
 * sarlavha o'rniga. Neon-gradient gapak (bubble) logotip + "YuzGram" wordmark.
 * Ranglar pastki navigatsiya (YuzGramBottomBar) palitrasi bilan bir xil:
 * PILL_START #38B6FF → PILL_END #5B6CF9.
 *
 * Joylashuv: action bar maydonining chapida, status bar hisobga olingan holda
 * vertikal markazlashtiriladi. DialogsActivity yuzBrandMark orqali boshqaradi
 * (skroll/sinxron yashirinish action bar bilan bir xil).
 */
public class YuzGramBrandMark extends View {

    private static final int PILL_START = 0xFF38B6FF;
    private static final int PILL_END = 0xFF5B6CF9;

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badgeRect = new RectF();
    private final Path bubblePath = new Path();
    private Typeface boldTypeface;

    private int statusBarHeight;
    private int barHeight; // action bar balandligi (status bar'siz)
    private float badgeSize;
    private float glyphSize;

    public YuzGramBrandMark(Context context) {
        super(context);
        badgeSize = AndroidUtilities.dp(25f);
        glyphSize = AndroidUtilities.dp(14f);
        boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        wordPaint.setTextSize(AndroidUtilities.dp(19.5f));
        wordPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        glyphPaint.setColor(0xFFFFFFFF);
        glyphPaint.setTextSize(glyphSize);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTypeface(boldTypeface);
    }

    /** DialogsActivity har sync'da haqiqiy o'lchamlarni uzatadi. */
    public void setBarHeights(int statusBar, int actionBarFullHeight) {
        int bar = Math.max(AndroidUtilities.dp(48), actionBarFullHeight - statusBar);
        if (statusBarHeight != statusBar || barHeight != bar) {
            statusBarHeight = statusBar;
            barHeight = bar;
            invalidate();
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float textW = wordPaint.measureText("YuzGram");
        int ideal = (int) (AndroidUtilities.dp(16) + badgeSize + AndroidUtilities.dp(8) + textW + AndroidUtilities.dp(10));
        int w = Math.min(MeasureSpec.getSize(widthMeasureSpec), ideal);
        int h = statusBarHeight + barHeight;
        setMeasuredDimension(w, Math.max(h, AndroidUtilities.dp(48)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cy = statusBarHeight + barHeight / 2f;
        float bx = AndroidUtilities.dp(16);
        float by = cy - badgeSize / 2f;
        float r = badgeSize * 0.32f;

        // Gradient tiklanadi (o'lcham/status bar o'zgarganda)
        badgePaint.setShader(new LinearGradient(bx, by, bx, by + badgeSize,
                PILL_START, PILL_END, Shader.TileMode.CLAMP));

        // Gapak: rounded rect + pastki chapdagi "quyruq" (chat applashuvchanligi)
        badgeRect.set(bx, by, bx + badgeSize, by + badgeSize);
        bubblePath.reset();
        bubblePath.addRoundRect(badgeRect, r, r, Path.Direction.CW);
        float tailY = by + badgeSize - r * 0.4f;
        bubblePath.moveTo(bx + r * 0.6f, tailY);
        bubblePath.lineTo(bx - AndroidUtilities.dp(3.5f), by + badgeSize + AndroidUtilities.dp(4f));
        bubblePath.lineTo(bx + r * 1.9f, by + badgeSize - AndroidUtilities.dp(1.2f));
        bubblePath.close();
        canvas.drawPath(bubblePath, badgePaint);

        // "Y" glyfi badge markazida (quyruq vizual og'irlik qiladi — 0.5dp o'ngga)
        canvas.drawText("Y", bx + badgeSize / 2f + AndroidUtilities.dp(0.5f),
                cy + glyphSize * 0.36f, glyphPaint);

        // Wordmark: oqdan och ko'kka silliq "dip" (neon brend, lekin oddiy)
        float tx = bx + badgeSize + AndroidUtilities.dp(8);
        float textW = wordPaint.measureText("YuzGram");
        wordPaint.setShader(new LinearGradient(tx, 0, tx + textW, 0,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFBFE0FF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        Paint.FontMetrics fm = wordPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("YuzGram", tx, baseline, wordPaint);
    }
}
