package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;

/**
 * YuzGram pastki navigatsiya bari — neon "floating dock".
 *
 * Tuzilishi (4 teng slot):  Asosiy | Reels | Elonlar | Profil
 * Markazdagi dumaloq "+" FAB olib tashlangan (e'lon/reel joylash tugmalari
 * o'z bo'limlari ichida: ElonlarActivity va ReelsActivity).
 *
 * Animatsiyalar:
 *  - Faol slot ostida gradientli "pill" — tab almashganda u spring (overshoot)
 *    bilan siljiydi va yo'lning o'rtasida cho'zilib, o'rniga qaytadi (morph).
 *  - Tanlangan ikon "pop" qiladi (kattalashib qaytadi) va atrofidan halqa tarqaydi.
 *  - Ikon/matn rangi va qalinligi pill bilan masofaga qarap crossfade bo'ladi
 *    (shu sababli siljish paytida ikkala tab ham qisman yorqin ko'rinadi).
 *  - Bosilganda slot cho'kadi (press), bar ko'rinmaganda pastdan spring bilan chiqadi.
 *
 * Ikonkalar Canvas'da chiziladi (resursga bog'liq emas), dark neon mavzu doimiy.
 * Brend palitrasining ko'klari YuzGramChatRail bilan bir xil (38B6FF -> 5B6CF9).
 */
public class YuzGramBottomBar extends View {

    public static final int TAB_ASOSIY = 0;
    public static final int TAB_REELS = 1;
    public static final int TAB_ELONLAR = 2;
    public static final int TAB_PROFIL = 3;
    public static final int COUNT = 4;

    /** Panelning umumiy balandligi (dip) — tepada panel + glow uchun bo'shliq bilan. */
    public static final int HEIGHT_DP = 68;

    private static final String[] LABELS = {"Asosiy", "Reels", "Funksiyalar", "Profil"};

    // Neon palitra (YuzGram brendi; rail bilan bir xil ko'klar)
    // YuzGram #96/#102/#108: sirt shaffof shisha — ostidan o'tayotgan chat
    // qatorlari aniq ko'rinadi (#102 talab: "orqasi korinsin"; #108: "bar
    // shaffof bolsin"). Alpha 0x73→0x4D: endi qatorlar bar ortida belgilan-
    // may, shuning uchun sirt yanada yengil bo'lishi mumkin. Kontrast
    // border + pill + soya hisobiga saqlanadi.
    private static final int SURFACE = 0x4D0C1626;      // chuqur to'q-ko'k yarim shaffof sirt
    private static final int BORDER = 0x662AABEE;       // brend-ko'k neon border (#2AABEE)
    private static final int GLOW_BLUE = 0x992AABEE;    // tashqi nur (#2AABEE)
    private static final int PILL_START = 0xFF38B6FF;   // och-ko'k (rail ACTIVE_START)
    private static final int PILL_END = 0xFF5B6CF9;     // brend indigo (rail ACTIVE_END)
    private static final int PILL_STROKE = 0x99A8E4FF; // pill ustidagi yorqin neon chet
    private static final int ICON_ACTIVE = 0xFFFFFFFF;  // faol ikon (pill ustida oq)
    private static final int ICON_IDLE = 0xFF8E9DBB;    // o'chiq ikon
    private static final int TEXT_ACTIVE = 0xFFE9F6FF;  // faol matn
    // 10.5sp kichik matn — sirt ustida kamida 4.5:1 kerak (bu juftlikda ~6.3:1).
    private static final int TEXT_IDLE = 0xFF8794AF;    // o'chiq matn

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF panelRect = new RectF();
    private final RectF pillRect = new RectF();
    private final RectF tmpRect = new RectF();
    private final Path path = new Path();
    private final Matrix shaderMatrix = new Matrix();

    private int activeTab = TAB_ASOSIY;
    private float indicatorPos = 0f;   // tab-space 0..COUNT-1, animatsiyalanadi
    private float stretch = 0f;        // 0..1 — pill siljish paytidagi cho'zilishi
    private int popTab = -1;           // "pop" animatsiyasi ketayotgan tab
    private float popAmount = 0f;      // 0..1
    private int pressSlot = -1;        // bosilayotgan slot (0..COUNT-1)
    private float pressAmount = 0f;    // 0..1
    private boolean shown = false;

    private OnTabSelectedListener listener;

    // Typeface'ni har kadrda yaratmaymiz (4 slot x 60 fps = tebranish)
    private Typeface regularTypeface;
    private Typeface boldTypeface;

    private final float textSize = AndroidUtilities.dp(10.5f);
    private final float iconSize = AndroidUtilities.dp(20.5f);

    // Geometriya (onDraw hisoblaydi)
    private float panelLeft, panelRight, panelTop, panelBottom;
    private final float[] slotCx = new float[COUNT];

    // Gradient faqat 1x1 birlik katakda quriladi; har kadrda Matrix orqali
    // pill o'lchamiga masshtablanadi — shuning uchun per-frame Shader yaratilmaydi.
    private LinearGradient pillGrad;

    public interface OnTabSelectedListener {
        void onTabSelected(int tab);
    }

    public YuzGramBottomBar(Context context) {
        super(context);
        init();
    }

    public YuzGramBottomBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setWillNotDraw(false);
        regularTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
        boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSize);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(1.9f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(AndroidUtilities.dp(1f));
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(AndroidUtilities.dp(1f));
        pillStrokePaint.setStyle(Paint.Style.STROKE);
        pillStrokePaint.setStrokeWidth(AndroidUtilities.dp(1.1f));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
        pillGrad = new LinearGradient(0f, 0f, 1f, 1f,
                PILL_START, PILL_END, Shader.TileMode.CLAMP);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // glow (setShadowLayer) uchun
        indicatorPos = activeTab;
    }

    public static int height() {
        return AndroidUtilities.dp(HEIGHT_DP);
    }

    /**
     * Balandlikni qat'iy dp(HEIGHT_DP)ga qotamiz. LaunchActivity'dagi frameLayout
     * (maxsus FrameLayout) bolaga ba'zan kutilganidan katta balandlik bermoqda —
     * bunda panel (panelBottom = h - dp(7)) butun ekranni egallab, ikonlar bilan
     * matn orasida ulkan bo'sh joy qolardi. EXACTLY spec bu xato holatdan qat'i-nazar
     * barni doim kompakt saqlaydi.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height(), MeasureSpec.EXACTLY));
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    public int getActiveTab() {
        return activeTab;
    }

    public void setActiveTab(int tab) {
        setActiveTab(tab, true);
    }

    public void setActiveTab(int tab, boolean animated) {
        if (tab < 0 || tab >= COUNT) {
            return;
        }
        boolean changed = tab != activeTab;
        activeTab = tab;
        if (changed) {
            animateIndicatorTo(tab, animated);
            animatePop(tab, animated);
        }
        invalidate();
    }

    private ValueAnimator indicatorAnim;
    private float travelFrom, travelTo;

    /**
     * Pill siljishi: masofaga qarab davomiylik (1 ta tab — tezkor, 3 ta tab —
     * sekinroq) va OvershootInterpolator — shuning uchun u o'rniga "urilib"
     * qaytadi (spring hissi). stretch yo'lning o'rtasida cho'zishni beradi.
     */
    private void animateIndicatorTo(int target, boolean animated) {
        if (indicatorAnim != null) {
            indicatorAnim.cancel();
        }
        travelFrom = indicatorPos;
        travelTo = target;
        if (!animated || Math.abs(travelTo - travelFrom) < 0.01f) {
            indicatorPos = target;
            stretch = 0f;
            invalidate();
            return;
        }
        float dist = Math.abs(travelTo - travelFrom);
        indicatorAnim = ValueAnimator.ofFloat(0f, 1f);
        indicatorAnim.setDuration((long) (250 + 45 * dist));
        indicatorAnim.setInterpolator(new OvershootInterpolator(1.7f));
        indicatorAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            indicatorPos = travelFrom + (travelTo - travelFrom) * f;
            float k = Math.max(0f, Math.min(1f, f));
            stretch = (float) Math.sin(Math.PI * k);
            invalidate();
        });
        indicatorAnim.start();
    }

    private ValueAnimator popAnim;

    /** Tanlangan ikon bir marta kattalashib qaytadi va halqa tarqaydi. */
    private void animatePop(int tab, boolean animated) {
        if (popAnim != null) {
            popAnim.cancel();
        }
        popTab = tab;
        if (!animated) {
            popAmount = 0f;
            return;
        }
        popAnim = ValueAnimator.ofFloat(0f, 1f, 0f);
        popAnim.setDuration(430);
        popAnim.setInterpolator(new DecelerateInterpolator(1.1f));
        popAnim.addUpdateListener(a -> {
            popAmount = (float) a.getAnimatedValue();
            invalidate();
        });
        popAnim.start();
    }

    private ValueAnimator pressAnim;

    private void animatePress(float to, long duration) {
        if (pressAnim != null) {
            pressAnim.cancel();
        }
        pressAnim = ValueAnimator.ofFloat(pressAmount, to);
        pressAnim.setDuration(duration);
        pressAnim.addUpdateListener(a -> {
            pressAmount = (float) a.getAnimatedValue();
            invalidate();
        });
        pressAnim.start();
    }

    /**
     * Barning ko'rinishini animatsiya bilan yoqish/o'chirish.
     *
     * Holat tekshiruvi `shown` flag'iga EMAS, balki View'ning haqiqiy
     * ko'rinishiga ham qaraydi: flag bilan sirt bir-biridan ajralib qolishi
     * mumkin (masalan yashirish animatsiyasi bekor bo'lib view GONE bo'lib
     * qolganda — eski kodda `shown == visible` erta return bundan keyin barni
     * bir umr ko'rsatmas edi). Bu yerda bunday zidlik o'z-o'zidan tiklanadi.
     */
    public void setVisible(boolean visible) {
        if (visible ? (shown && getVisibility() == VISIBLE)
                : (!shown && getVisibility() == GONE)) {
            return;
        }
        shown = visible;
        animate().cancel();
        if (visible) {
            setVisibility(VISIBLE);
            if (getAlpha() >= 1f) {
                setAlpha(0f);
                setTranslationY(height() * 0.7f);
            }
            animate().alpha(1f).translationY(0f).setDuration(300)
                    .setInterpolator(new OvershootInterpolator(0.55f)).start();
        } else {
            animate().alpha(0f).translationY(height() * 0.85f).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    public boolean isShownBar() {
        return shown;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float sideMargin = AndroidUtilities.dp(10);
        float bottomMargin = AndroidUtilities.dp(7);
        panelLeft = sideMargin;
        panelRight = w - sideMargin;
        panelTop = AndroidUtilities.dp(8);
        panelBottom = h - bottomMargin;
        float corner = AndroidUtilities.dp(23);
        panelRect.set(panelLeft, panelTop, panelRight, panelBottom);

        float usable = panelRight - panelLeft;
        for (int i = 0; i < COUNT; i++) {
            slotCx[i] = panelLeft + usable * (i + 0.5f) / COUNT;
        }

        float iconCy = panelTop + AndroidUtilities.dp(18.5f);
        float textBaseline = panelBottom - AndroidUtilities.dp(9);
        float pillHalfBase = AndroidUtilities.dp(20f);
        float pillHalfH = AndroidUtilities.dp(13f);
        float ind = clampIndicator();

        // --- Panel sirti + tashqi neon glow ---
        int layer = canvas.saveLayer(
                panelLeft - AndroidUtilities.dp(20), panelTop - AndroidUtilities.dp(20),
                panelRight + AndroidUtilities.dp(20), panelBottom + AndroidUtilities.dp(20), null);

        surfacePaint.setColor(SURFACE);
        surfacePaint.setShadowLayer(AndroidUtilities.dp(15), 0, AndroidUtilities.dp(2), GLOW_BLUE);
        canvas.drawRoundRect(panelRect, corner, corner, surfacePaint);
        surfacePaint.clearShadowLayer();

        // border + tepadagi nozik yorug' chiziq (sirt "ustidagi" neon chiroyi)
        borderPaint.setColor(BORDER);
        canvas.drawRoundRect(panelRect, corner, corner, borderPaint);
        highlightPaint.setColor(0x22FFFFFF);
        tmpRect.set(panelLeft + AndroidUtilities.dp(16), panelTop + AndroidUtilities.dp(0.6f),
                panelRight - AndroidUtilities.dp(16), panelTop + AndroidUtilities.dp(0.7f));
        canvas.drawLine(tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.top, highlightPaint);
        canvas.restoreToCount(layer);

        // --- Faol pill (siljuvchi + cho'ziluvchi) ---
        float pillCx = panelLeft + usable * (ind + 0.5f) / COUNT;
        float pillHalfW = pillHalfBase + AndroidUtilities.dp(10f) * stretch;
        pillRect.set(pillCx - pillHalfW, iconCy - pillHalfH,
                pillCx + pillHalfW, iconCy + pillHalfH);
        // Mulohaza: Paint rangi Shader'ga alpha'ni "modulyatsiya" qiladi —
        // oldin qolgan alpha gradientni xiralashtiradi, shuning uchun AVVAL oq.
        pillPaint.setColor(0xFFFFFFFF);
        shaderMatrix.reset();
        shaderMatrix.setTranslate(pillRect.left, pillRect.top);
        shaderMatrix.preScale(pillRect.width(), pillRect.height());
        pillGrad.setLocalMatrix(shaderMatrix);
        pillPaint.setShader(pillGrad);
        pillPaint.setAlpha((int) (235 - 40 * pressAmount));
        pillPaint.setShadowLayer(AndroidUtilities.dp(11), 0, AndroidUtilities.dp(1.5f), PILL_START);
        canvas.drawRoundRect(pillRect, pillHalfH, pillHalfH, pillPaint);
        pillPaint.clearShadowLayer();
        pillPaint.setShader(null);
        pillStrokePaint.setColor(PILL_STROKE);
        canvas.drawRoundRect(pillRect, pillHalfH, pillHalfH, pillStrokePaint);

        // --- "pop" halqasi: tanlangan ikon atrofidan tarqaydi ---
        if (popTab >= 0 && popAmount > 0.01f) {
            float rc = AndroidUtilities.dp(13f) + AndroidUtilities.dp(17f) * popAmount;
            ringPaint.setColor(PILL_START);
            ringPaint.setAlpha((int) (110 * (1f - popAmount)));
            canvas.drawCircle(slotCx[popTab], iconCy, rc, ringPaint);
        }

        // --- Slotlar: ikon + matn (masofaga qarab crossfade) ---
        for (int i = 0; i < COUNT; i++) {
            float prox = 1f - Math.min(1f, Math.abs(i - ind));
            float squeeze = i == pressSlot ? 1f - 0.07f * pressAmount : 1f;
            float cx = slotCx[i];

            boolean bold = prox > 0.5f;
            int iconColor = lerpColor(ICON_IDLE, ICON_ACTIVE, easeOut(prox));
            int textColor = lerpColor(TEXT_IDLE, TEXT_ACTIVE, easeOut(prox));

            float scale = (1f + 0.1f * prox) * squeeze;
            if (popTab == i && popAmount > 0.01f) {
                scale *= 1f + 0.16f * popAmount;
            }
            drawIcon(canvas, i, cx, iconCy, iconSize * scale, iconColor, bold);

            textPaint.setColor(textColor);
            textPaint.setTypeface(bold ? boldTypeface : regularTypeface);
            float ty = textBaseline + (1f - prox) * AndroidUtilities.dp(0.8f);
            canvas.drawText(LABELS[i], cx, ty, textPaint);
        }
    }

    /**
     * indicatorPos spring sababli chegaradan biroz chiqib qoladi (overshoot) —
     * geometriyani 0..COUNT-1 oralig'ida ushlab turamiz, aks holda pill panel
     * chetidan chiqib ketadi.
     */
    private float clampIndicator() {
        return Math.max(-0.30f, Math.min(COUNT - 1 + 0.30f, indicatorPos));
    }

    private static float easeOut(float t) {
        float k = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - k) * (1f - k);
    }

    private static int lerpColor(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xff) + (((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * k);
        int r = (int) (((from >> 16) & 0xff) + (((to >> 16) & 0xff) - ((from >> 16) & 0xff)) * k);
        int g = (int) (((from >> 8) & 0xff) + (((to >> 8) & 0xff) - ((from >> 8) & 0xff)) * k);
        int b = (int) ((from & 0xff) + ((to & 0xff) - (from & 0xff)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Ikon to'plami — bitta chiziq oilasi (yumaloq uchli shtrip, bir xil
     * burchak radiusi formulasi) va bitta faollik tili: tanlangan slotda
     * ikonning "imzo" elementi to'ldiriladi (quloqcha / play / katak / bosh).
     *
     * Barcha o'lchamlar `k = size / iconSize` bilan masshtablanadi: ikon
     * "pop" qilganda geometriya ham birga o'sadi, aks holda (eski kodda
     * qo'l bilan berilgan dp(6), dp(11) kabi ofsetlar) shakl buzilib,
     * tanlangan ikon qiyshayib ko'rinardi.
     */
    private void drawIcon(Canvas canvas, int tab, float cx, float cy, float size, int color, boolean active) {
        iconPaint.setColor(color);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(active ? 2.05f : 1.75f));
        float k = size / iconSize;
        float half = size / 2f;
        switch (tab) {
            case TAB_ASOSIY: {
                // Suhbat ko'pigi + chap-pastdagi quloqcha
                tmpRect.set(cx - half, cy - half + AndroidUtilities.dp(1f) * k,
                        cx + half, cy + half - AndroidUtilities.dp(6f) * k);
                drawRoundRectStroke(canvas, tmpRect, AndroidUtilities.dp(5.5f) * k);
                path.reset();
                path.moveTo(tmpRect.left + AndroidUtilities.dp(6.5f) * k, tmpRect.bottom);
                path.lineTo(tmpRect.left + AndroidUtilities.dp(3.5f) * k,
                        tmpRect.bottom + AndroidUtilities.dp(5.2f) * k);
                path.lineTo(tmpRect.left + AndroidUtilities.dp(11.5f) * k, tmpRect.bottom);
                path.close();
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
                break;
            }
            case TAB_REELS: {
                // Reels: yumaloq kadr ichida "play" uchburchagi.
                // Uchburchak og'irlik markazi ko'rgazgich markaziga yaqin
                // bo'lishi kerak — chap qirrani biroz chamalasak, "o'ngga
                // siljigan" hissi yo'qoladi (optik markazlash).
                tmpRect.set(cx - half, cy - half, cx + half, cy + half);
                drawRoundRectStroke(canvas, tmpRect, AndroidUtilities.dp(6f) * k);
                float tri = AndroidUtilities.dp(4.7f) * k;
                path.reset();
                path.moveTo(cx - tri * 0.55f, cy - tri);
                path.lineTo(cx - tri * 0.55f, cy + tri);
                path.lineTo(cx + tri * 1.12f, cy);
                path.close();
                iconPaint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
                break;
            }
            case TAB_ELONLAR: {
                // Funksiyalar: 2x2 to'plam to'rini; faol slotda yuqori-chap katak to'ldiriladi
                float gap = AndroidUtilities.dp(3f) * k;
                float cell = (size - gap) / 2f;
                float left = cx - half, top = cy - half;
                for (int gx = 0; gx < 2; gx++) {
                    for (int gy = 0; gy < 2; gy++) {
                        tmpRect.set(
                                left + gx * (cell + gap),
                                top + gy * (cell + gap),
                                left + gx * (cell + gap) + cell,
                                top + gy * (cell + gap) + cell);
                        if (active && gx == 0 && gy == 0) {
                            iconPaint.setStyle(Paint.Style.FILL);
                            canvas.drawRoundRect(tmpRect, AndroidUtilities.dp(3.4f) * k,
                                    AndroidUtilities.dp(3.4f) * k, iconPaint);
                            iconPaint.setStyle(Paint.Style.STROKE);
                        } else {
                            drawRoundRectStroke(canvas, tmpRect, AndroidUtilities.dp(3.4f) * k);
                        }
                    }
                }
                break;
            }
            case TAB_PROFIL: {
                float headR = AndroidUtilities.dp(4.3f) * k;
                float headCy = cy - AndroidUtilities.dp(3.8f) * k;
                if (active) {
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, headCy, headR, iconPaint);
                    iconPaint.setStyle(Paint.Style.STROKE);
                } else {
                    canvas.drawCircle(cx, headCy, headR, iconPaint);
                }
                path.reset();
                tmpRect.set(cx - half * 0.96f, cy + AndroidUtilities.dp(1.4f) * k,
                        cx + half * 0.96f, cy + half + AndroidUtilities.dp(6.6f) * k);
                path.addArc(tmpRect, 200f, 140f);
                canvas.drawPath(path, iconPaint);
                break;
            }
        }
    }

    private void drawRoundRectStroke(Canvas canvas, RectF rect, float r) {
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.drawPath(path, iconPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            super.onTouchEvent(event);
            int slot = slotAt(event.getX());
            if (slot >= 0) {
                pressSlot = slot;
                animatePress(1f, 90);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            int slot = slotAt(event.getX());
            if (slot != pressSlot) {
                pressSlot = slot;
                animatePress(slot >= 0 ? 1f : 0f, 110);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            int slot = pressSlot;
            pressSlot = -1;
            animatePress(0f, 200);
            if (slot >= 0) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                if (slot != activeTab) {
                    setActiveTab(slot);
                } else {
                    // qayta bosish: indicator joyida, faqat ikon "pop" qiladi
                    animatePop(slot, true);
                }
                if (listener != null) {
                    listener.onTabSelected(slot);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            pressSlot = -1;
            animatePress(0f, 200);
            return super.onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    /**
     * Bosingan x koordinatani slot indeksiga aylantiradi. Markaz FAB yo'q — 4 teng
     * slot, shuning uchun panel kengligi bo'ylab istalgan joyda urish ishlaydi.
     */
    private int slotAt(float x) {
        if (panelRight <= panelLeft) {
            return -1;
        }
        if (x < panelLeft || x > panelRight) {
            return -1;
        }
        int slot = (int) ((x - panelLeft) / ((panelRight - panelLeft) / COUNT));
        return Math.max(0, Math.min(COUNT - 1, slot));
    }
}
