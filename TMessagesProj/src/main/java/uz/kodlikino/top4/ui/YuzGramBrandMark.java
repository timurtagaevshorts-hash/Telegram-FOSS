package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/**
 * YuzGram #60: chat tepasidagi brend belgisi — oddiy matnli sarlavha o'rniga.
 * YuzGram #85: maketdagi ko'rinishga moslandi — dumaloq gradient nishon
 * (qog'oz samolyot) + ikki rangli "YuzGram" palavrasi + ostida tagline.
 *
 * Palavras ENDI gradient emas: "Yuz" oq, "Gram" to'q ko'k (#38B6FF). Bir xil
 * rangli sirtga gradient matn qo'yish o'qishni buzadi; vazn va rang ajratmasi
 * yetarli.
 *
 * Joylashuv: action bar maydonining chapida, status bar hisobga olinadi.
 * DialogsActivity yuzBrandMark orqali boshqaradi (skroll/action-mode sinxroni).
 */
public class YuzGramBrandMark extends View {

    private static final int PILL_START = 0xFF38B6FF;
    private static final int PILL_END = 0xFF5B6CF9;
    private static final int WORD_PRIMARY = 0xFFFFFFFF;
    private static final int WORD_ACCENT = 0xFF38B6FF;
    private static final int TAGLINE = 0xFF93AECB;

    private static final String WORD_1 = "Yuz";
    private static final String WORD_2 = "Gram";

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badgeRect = new RectF();
    private final Path planePath = new Path();
    private final String tagline;
    private String tagText = "";

    private int statusBarHeight;
    private int barHeight; // action bar balandligi (status bar'siz)
    private float badgeSize = 30f;
    /** Tor ekranlarda tagline action bar tugmalari bilan urishadi — shunda chizilmaydi. */
    private boolean showTagline = true;
    // Gradientlar har kadrda yaratilmaydi: markaz (status bar + bar balandligi)
    // o'zgargandagina qayta tiklanadi.
    private LinearGradient badgeShader;
    private RadialGradient haloShader;
    private float shaderCy = Float.MIN_VALUE;

    public YuzGramBrandMark(Context context) {
        super(context);
        tagline = context.getString(R.string.yuz_chat_tagline);
        badgeSize = AndroidUtilities.dp(30f);
        Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        wordPaint.setTextSize(AndroidUtilities.dp(20f));
        wordPaint.setTypeface(bold);
        tagPaint.setTextSize(AndroidUtilities.dp(10.5f));
        tagPaint.setColor(TAGLINE);
        tagPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Material "send" yo'li (24x24 katak: x 2..23, y 3..21) 0..1 box'ga
        // keltirilgan — nishon hajmi o'zgarganda qayta hisoblamaymiz.
        planePath.moveTo(0.001f, 1.000f);
        planePath.lineTo(1.000f, 0.500f);
        planePath.lineTo(0.001f, 0.000f);
        planePath.lineTo(0.000f, 0.389f);
        planePath.lineTo(0.714f, 0.500f);
        planePath.lineTo(0.000f, 0.611f);
        planePath.close();
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
        float wordW = wordPaint.measureText(WORD_1) + wordPaint.measureText(WORD_2);
        float tagW = tagPaint.measureText(tagline);
        // O'ngda action bar tugmalari (lupa/menu) turadi — ular bilan
        // urishmaslik uchun ~76dp bo'sh joy qoldiramiz. Sig'masa, avval
        // tagline qisqaradi, keyin butunlay tushib qoladi.
        int specW = MeasureSpec.getSize(widthMeasureSpec);
        float avail = specW - AndroidUtilities.dp(16) - badgeSize - AndroidUtilities.dp(9)
                - AndroidUtilities.dp(76);
        tagText = tagW <= avail ? tagline : (avail > AndroidUtilities.dp(48) ? fitTail(tagline, avail) : "");
        showTagline = !tagText.isEmpty();
        float textW = Math.max(wordW, tagPaint.measureText(tagText));
        int ideal = (int) (AndroidUtilities.dp(16) + badgeSize + AndroidUtilities.dp(9)
                + textW + AndroidUtilities.dp(12));
        int w = Math.min(specW, ideal);
        int h = statusBarHeight + barHeight;
        setMeasuredDimension(w, Math.max(h, AndroidUtilities.dp(48)));
    }

    private String fitTail(String text, float maxW) {
        int end = text.length();
        while (end > 1 && tagPaint.measureText(text.substring(0, end) + "…") > maxW) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = statusBarHeight + barHeight / 2f;
        float bx = AndroidUtilities.dp(16);
        float by = cx - badgeSize / 2f;

        if (shaderCy != cx) {
            shaderCy = cx;
            haloShader = new RadialGradient(bx + badgeSize / 2f, by + badgeSize * 0.62f,
                    badgeSize * 0.95f, 0x4038B6FF, 0, Shader.TileMode.CLAMP);
            badgeShader = new LinearGradient(bx, by, bx + badgeSize, by + badgeSize,
                    PILL_START, PILL_END, Shader.TileMode.CLAMP);
        }

        // Nishon ostidagi nur — tekis doira emas, chuqurlik (ofset + yumshatish).
        glowPaint.setShader(haloShader);
        canvas.drawCircle(bx + badgeSize / 2f, by + badgeSize * 0.62f, badgeSize * 0.95f, glowPaint);
        glowPaint.setShader(null);

        badgePaint.setShader(badgeShader);
        badgeRect.set(bx, by, bx + badgeSize, by + badgeSize);
        canvas.drawOval(badgeRect, badgePaint);
        badgePaint.setShader(null);

        // Qog'oz samolyot
        float gw = badgeSize * 0.55f;
        float gh = gw * 18f / 21f;
        canvas.save();
        canvas.translate(bx + (badgeSize - gw) / 2f, cx - gh / 2f);
        canvas.scale(gw, gh);
        badgePaint.setColor(WORD_PRIMARY);
        canvas.drawPath(planePath, badgePaint);
        canvas.restore();
        badgePaint.setColor(0);

        // Palavra: "Yuz" oq + "Gram" ko'k — gradient matn emas.
        float tx = bx + badgeSize + AndroidUtilities.dp(9);
        float wordBand = AndroidUtilities.dp(22f);
        float tagBand = AndroidUtilities.dp(13f);
        float blockTop = cx - (wordBand + (showTagline ? tagBand : 0f)) / 2f;

        Paint.FontMetrics wfm = wordPaint.getFontMetrics();
        float wordBaseline = blockTop + wordBand / 2f - (wfm.ascent + wfm.descent) / 2f;
        wordPaint.setColor(WORD_PRIMARY);
        canvas.drawText(WORD_1, tx, wordBaseline, wordPaint);
        float w1 = wordPaint.measureText(WORD_1);
        wordPaint.setColor(WORD_ACCENT);
        canvas.drawText(WORD_2, tx + w1, wordBaseline, wordPaint);
        wordPaint.setColor(WORD_PRIMARY);

        // Tagline — palavrasining ostida, bir xil chap chetdan
        if (showTagline) {
            Paint.FontMetrics tfm = tagPaint.getFontMetrics();
            float tagBaseline = blockTop + wordBand + tagBand / 2f - (tfm.ascent + tfm.descent) / 2f;
            canvas.drawText(tagText, tx, tagBaseline, tagPaint);
        }
    }
}
