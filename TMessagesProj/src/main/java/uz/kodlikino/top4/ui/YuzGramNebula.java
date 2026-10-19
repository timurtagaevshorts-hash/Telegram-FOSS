package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/**
 * YuzGram #85: chat ro'yxati ORQASIDagi nebula fon. Tekis qora o'rniga brend
 * palitrasidagi chuqurlik: chuqur ko'm-ko'k asos + ikki yumshoq nur manbasi
 * (yuqori-o'ng to'q sariq-binafsha, past-chap ko'k).
 *
 * Nurlar ATALADIGAN yorug'lik emas, past alfa bilan chiziladi — chat qatorlari
 * matnining kontrast (4.5:1) shu fon ustida ham saqlanishi uchun.
 */
public class YuzGramNebula extends View {

    private static final int BASE_TOP = 0xFF0D1830;
    private static final int BASE_BOTTOM = 0xFF070D1A;
    private static final int GLOW_VIOLET = 0x335B6CF9;
    private static final int GLOW_CYAN = 0x2E2AABEE;
    private static final int GLOW_AZURE = 0x2438B6FF;

    private final Paint basePaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int builtW = -1, builtH = -1;
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
        if (w <= 0 || h <= 0 || (w == builtW && h == builtH)) {
            return;
        }
        builtW = w;
        builtH = h;
        baseShader = new LinearGradient(0, 0, 0, h, BASE_TOP, BASE_BOTTOM, Shader.TileMode.CLAMP);
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
        violetShader = new RadialGradient(violetCx, violetCy, violetR, GLOW_VIOLET, 0, Shader.TileMode.CLAMP);
        cyanShader = new RadialGradient(cyanCx, cyanCy, cyanR, GLOW_CYAN, 0, Shader.TileMode.CLAMP);
        azureShader = new RadialGradient(azureCx, azureCy, azureR, GLOW_AZURE, 0, Shader.TileMode.CLAMP);
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
    protected void onDetachedFromWindow() {
        glowPaint.setShader(null);
        basePaint.setShader(null);
        super.onDetachedFromWindow();
    }
}
