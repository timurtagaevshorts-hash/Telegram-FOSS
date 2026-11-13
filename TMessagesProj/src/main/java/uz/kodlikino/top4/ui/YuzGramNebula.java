package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram #85: chat ro'yxati ORQASIDAGI nebula fon. Tekis qora o'rniga brend
 * palitrasidagi chuqurlik: chuqur ko'm-ko'k asos + ikki yumshoq nur manbasi
 * (yuqori-o'ng to'q sariq-binafsha, past-chap ko'k).
 *
 * Nurlar ATALADIGAN yorug'lik emas, past alfa bilan chiziladi — chat qatorlari
 * matnining kontrast (4.5:1) shu fon ustida ham saqlanishi uchun.
 *
 * r63: kunduzgi mavzuda baza TOZA OQ (#FFFFFF), nurlar esa pushti brendning
 * juda past alfa yuvindisi; tungi mavzuda baza qora bo'lib qoladi.
 */
public class YuzGramNebula extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final int DARK_BASE_TOP = 0xFF000000;
    private static final int DARK_BASE_BOTTOM = 0xFF000000;
    private static final int DARK_GLOW_VIOLET = 0x33FF5C93;
    private static final int DARK_GLOW_CYAN = 0x2EC2185B;
    private static final int DARK_GLOW_AZURE = 0x24E0245E;

    private static final int LIGHT_BASE_TOP = 0xFFFFFFFF;
    private static final int LIGHT_BASE_BOTTOM = 0xFFFFFFFF;
    private static final int LIGHT_GLOW_VIOLET = 0x14D81B60;
    private static final int LIGHT_GLOW_CYAN = 0x10C2185B;
    private static final int LIGHT_GLOW_AZURE = 0x0DE0245E;

    private final Paint basePaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int builtW = -1, builtH = -1;
    private boolean builtDark;
    private LinearGradient baseShader;
    private RadialGradient violetShader, cyanShader, azureShader;
    private float violetCx, violetCy, violetR;
    private float cyanCx, cyanCy, cyanR;
    private float azureCx, azureCy, azureR;

    public YuzGramNebula(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void build(int w, int h) {
        boolean dark = Theme.isCurrentThemeDark();
        if (w <= 0 || h <= 0 || (w == builtW && h == builtH && dark == builtDark)) {
            return;
        }
        builtW = w;
        builtH = h;
        builtDark = dark;
        int baseTop;
        int baseBottom;
        int glowViolet;
        int glowCyan;
        int glowAzure;
        if (dark) {
            baseTop = DARK_BASE_TOP;
            baseBottom = DARK_BASE_BOTTOM;
            glowViolet = DARK_GLOW_VIOLET;
            glowCyan = DARK_GLOW_CYAN;
            glowAzure = DARK_GLOW_AZURE;
        } else {
            baseTop = LIGHT_BASE_TOP;
            baseBottom = LIGHT_BASE_BOTTOM;
            glowViolet = LIGHT_GLOW_VIOLET;
            glowCyan = LIGHT_GLOW_CYAN;
            glowAzure = LIGHT_GLOW_AZURE;
        }
        baseShader = new LinearGradient(0, 0, 0, h, baseTop, baseBottom, Shader.TileMode.CLAMP);
        float big = Math.max(w, h);
        // Binafsha nur sarlavha blokining (gildirak + qidiruv) ortida gullaydi:
        // tepadan ~0.2h — action bar osti. Nurlar yorug'lik emas, past alfa.
        violetCx = w * 0.86f;
        violetCy = h * 0.20f;
        violetR = big * 0.58f;
        cyanCx = w * 0.02f;
        cyanCy = h * 0.50f;
        cyanR = big * 0.50f;
        azureCx = w * 0.72f;
        azureCy = h * 0.99f;
        azureR = big * 0.44f;
        violetShader = new RadialGradient(violetCx, violetCy, violetR, glowViolet, 0, Shader.TileMode.CLAMP);
        cyanShader = new RadialGradient(cyanCx, cyanCy, cyanR, glowCyan, 0, Shader.TileMode.CLAMP);
        azureShader = new RadialGradient(azureCx, azureCy, azureR, glowAzure, 0, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        build(w, h);
        basePaint.setShader(baseShader);
        canvas.drawRect(0, 0, w, h, basePaint);
        basePaint.setShader(null);

        canvas.drawCircle(violetCx, violetCy, violetR, shaderPaint(violetShader));
        canvas.drawCircle(cyanCx, cyanCy, cyanR, shaderPaint(cyanShader));
        canvas.drawCircle(azureCx, azureCy, azureR, shaderPaint(azureShader));
    }

    private Paint shaderPaint(Shader shader) {
        glowPaint.setShader(shader);
        return glowPaint;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        glowPaint.setShader(null);
        basePaint.setShader(null);
        super.onDetachedFromWindow();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            // Mavzu almashdi — geyzerlar keshini tashlaymiz va qayta chizamiz.
            builtW = -1;
            builtH = -1;
            invalidate();
        }
    }
}
