package uz.kodlikino.top4.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.ArrayList;

/**
 * YuzGram r60: chat ro'yxati ustida suzuvchi YARIM OY filtri — tekis tomoni
 * ekranning o'ng chetiga yopishgan shisha yarim disk. Filtr donalari disk
 * ichidagi konsentrik yoy bo'ylab teriladi, nomi donaning CHAP tomonida,
 * qalamcha esa tekis tomonning markazida turadi. Barmoq vertikal suriladi;
 * qo'yib yuborilganda inertsiya bilan eng yaqin tugmaga qonadi.
 *
 * Kontrakt o'zgarmaydi: setFilters/setListener/setCounterProvider/
 * setActionListener/setOnAddFolder. Joylash: Gravity.RIGHT|BOTTOM,
 * o'lchov statik width()/height().
 */
public class YuzGramChatWheel extends View {

    /** Ustun kengligi — chap qismi shaffof, faqat nomlar uchun joy. */
    public static final int WIDTH_DP = 176;
    /** Ustun balandligi. */
    public static final int HEIGHT_DP = 196;

    /** Yarim disk: markaz ekranning o'ng chetida (x = width). */
    private static final float DISC_R_DP = 92f;
    private static final float DISC_CY_DP = 96f;
    /** Donalar yoyi: disk markazidan radius (qirradan 28dp ichkarida). */
    private static final float ARC_R_DP = 64f;
    /** Qo'shni donalar orasidagi burchak (rad). */
    private static final float ARC_STEP = 0.62f;
    /** Fokusdan shuncha dona uzoqda dona butunlay yo'qoladi. */
    private static final float CULL_SLOTS = 2.2f;
    private static final float CIRCLE_DP = 22f;
    /** Surish paytida menyu shu nisbatda kattalashadi (dam olish = 1.0). */
    private static final float SCALE_DRAG = 1.08f;
    /** Qalamcha: tekis tomonning markazida (kichik) va top-rejimda (katta). */
    private static final float SMALL_COMPOSE_R_DP = 16f;
    private static final float SMALL_COMPOSE_FROM_EDGE_DP = 24f;
    private static final float BIG_COMPOSE_R_DP = 24f;
    private static final float BIG_COMPOSE_FROM_EDGE_DP = 52f;
    /** Yuqoriga/pastga ko'chirish belgisi — yarim oyning tepa-chap yelkasi. */
    private static final float TOGGLE_CX_DP = 104f;
    private static final float TOGGLE_CY_DP = 22f;
    private static final float TOGGLE_TOUCH_DP = 40f;

    // --- palitra: binafsha (violet) brend ---
    private static final int BORDER = 0x66852AEE;
    private static final int GLOW = 0x7A852AEE;
    private static final int ACTIVE_START = 0xFF9B6BFF;
    private static final int ACTIVE_END = 0xFF6E2BEE;
    private static final int INACTIVE_FILL = 0x66301B5C;
    private static final int INACTIVE_STROKE = 0x73A98CFF;
    private static final int ICON_ACTIVE = 0xFFFFFFFF;
    private static final int ICON_IDLE = 0xFFD3C6F5;
    /** Nom — fokusda oq, boshqalarda shisha-binafsha. */
    private static final int LABEL_FOCUS = 0xFFFFFFFF;
    private static final int LABEL_IDLE = 0xBFC6B4EC;
    /** O'qilmaganlar soni — aksent ustida oq (5.9:1). */
    private static final int COUNT_BG = 0xFF852AEE;
    private static final int COUNT_TEXT = 0xFFFFFFFF;
    /** Yarim oy korpusi: chuqur siyoh -> binafsha shisha. */
    private static final int PANEL_TOP = 0x991A0E2E;
    private static final int PANEL_MID = 0xB32B1A52;
    private static final int PANEL_BOTTOM = 0x99170C28;
    private static final int RIM = 0x8CA98CFF;
    private static final int RIM_BRIGHT = 0xBFCEA8FF;
    private static final int GLOW_OUT = 0x26852AEE;
    private static final int SHEEN = 0x3DC0A8FF;
    /** Qalamcha: grafit uchi va o'chirg'ich bandi. */
    private static final int PENCIL_TIP = 0xFF2A1046;

    public static final int ACTION_COMPOSE = 0;
    public static final int ACTION_CAMERA = 1;

    private static final int ITEM_FILTER = 0;
    private static final int ITEM_COMPOSE = 1;
    private static final int ITEM_ADD = 2;

    public interface Listener {
        void onFolderSelected(int index);
    }

    public interface ActionListener {
        void onActionClicked(int action);
    }

    public interface CounterProvider {
        int getCounter(MessagesController.DialogFilter filter);
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
    private Listener listener;
    private ActionListener actionListener;
    private CounterProvider counterProvider;
    private Runnable addFolderAction;
    private boolean showCompose = true;
    private boolean addTile;

    private float pos;           // reyka pozitsiyasi (dona birligida)
    private int selectedIndex = 0;
    /** Yarim oyning umumiy masshtabi: tegilganda sal o'sadi, qo'yilganda qaytadi. */
    private float menuScale = 1f;
    private ValueAnimator scaleAnim;
    private float composeScale = 1f;
    private ValueAnimator composeAnim;

    private float downY, downPos;
    private float lastY, lastT;
    private float velocity;      // px/s
    private boolean dragging;
    private int pressItem = -1;  // bosilgan dona indeksi (-1 yo'q)
    private ValueAnimator animator;
    private ValueAnimator pulse;
    private float pulseT = 1f;

    private LinearGradient activeShader;
    private LinearGradient bigShader;
    private LinearGradient smallShader;
    private RadialGradient focusGlow;
    private LinearGradient panelShader;
    private LinearGradient rimShader;
    private RadialGradient sheenShader;
    private float panelShaderKey = Float.MIN_VALUE;
    private float shaderCx = Float.MIN_VALUE, shaderCy = Float.MIN_VALUE, shaderR = Float.MIN_VALUE;
    private float glowCx = Float.MIN_VALUE, glowCy = Float.MIN_VALUE, glowR = Float.MIN_VALUE;
    private float bigShaderCx = Float.MIN_VALUE, bigShaderCy = Float.MIN_VALUE;
    private float smallShaderCx = Float.MIN_VALUE, smallShaderCy = Float.MIN_VALUE;
    /** Fokus nomining asosiy o'lchami (sp) — sig'imganda shundan pastga tushadi. */
    private float labelBaseSize;

    /** Top-tab rejimi: yarim oy o'rniga katta qalamcha (0 = yarim oy, 1 = qalamcha). */
    private boolean topTabsMode;
    private float topBlend;
    private ValueAnimator topBlendAnim;
    private boolean composePressed;
    private boolean composeTracking;
    private float composeDownX, composeDownY;

    public YuzGramChatWheel(Context context) {
        super(context);
        setClickable(true);
        setWillNotDraw(false);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.4f));
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(2f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        labelBaseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f,
                getResources().getDisplayMetrics());
        textPaint.setTextSize(labelBaseSize);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        countPaint.setTextAlign(Paint.Align.CENTER);
        countPaint.setColor(COUNT_TEXT);
        countPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public static int width() {
        return AndroidUtilities.dp(WIDTH_DP);
    }

    public static int height() {
        return AndroidUtilities.dp(HEIGHT_DP);
    }

    /** Ko'chirish belgisi uchun o'ng bo'shliq (40dp teginish maydoni bilan). */
    public static int toggleRightMarginDp() {
        return Math.round(TOGGLE_CX_DP - TOGGLE_TOUCH_DP / 2f);
    }

    /** Ko'chirish belgisining pastdan masofasi (view chetidan). */
    public static int toggleBottomInsetDp() {
        return Math.round(HEIGHT_DP - TOGGLE_CY_DP - TOGGLE_TOUCH_DP / 2f);
    }

    // ------------------------------------------------------------ API

    public void setListener(Listener l) {
        listener = l;
    }

    public void setActionListener(ActionListener l) {
        actionListener = l;
    }

    public void setCounterProvider(CounterProvider p) {
        counterProvider = p;
    }

    public void setOnAddFolder(Runnable r) {
        addFolderAction = r;
    }

    public void setActions(boolean compose, boolean camera) {
        // Kamera #70 dan keyin chatda kerak emas (storylar Reels'ga ko'chdi).
        if (showCompose != compose) {
            showCompose = compose;
            requestLayout();
            invalidate();
        }
    }

    public void setFilters(ArrayList<MessagesController.DialogFilter> newFilters,
                           int selected, boolean showAdd, boolean animated) {
        boolean countChanged = newFilters != null && newFilters.size() != filters.size();
        addTile = showAdd;
        filters = newFilters != null ? newFilters : new ArrayList<>();
        boolean moved = Math.abs(pos - selected) > 0.001f;
        if (selected < 0 || selected >= Math.max(1, filters.size())) {
            selected = 0;
        }
        selectedIndex = selected;
        if (animated && moved && filters.size() > 1) {
            animateTo(selected, false);
        } else {
            cancelSlide();
            pos = selected;
            if (moved || countChanged) {
                invalidate();
            }
        }
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < filters.size() && index != selectedIndex) {
            selectedIndex = index;
            animateTo(index, false);
        }
    }

    /** Top-tab rejimida yarim oy o'rniga katta qalamcha ko'rsatiladi. */
    public void setTopTabsMode(boolean on, boolean animated) {
        if (topTabsMode == on) {
            return;
        }
        topTabsMode = on;
        if (topBlendAnim != null) {
            topBlendAnim.cancel();
            topBlendAnim = null;
        }
        float target = on ? 1f : 0f;
        if (!animated) {
            topBlend = target;
            cancelSlide();
            invalidate();
            return;
        }
        topBlendAnim = ValueAnimator.ofFloat(topBlend, target);
        topBlendAnim.setDuration(220);
        topBlendAnim.setInterpolator(new DecelerateInterpolator());
        topBlendAnim.addUpdateListener(a -> {
            topBlend = (float) a.getAnimatedValue();
            invalidate();
        });
        topBlendAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                topBlendAnim = null;
            }
        });
        topBlendAnim.start();
    }

    // --------------------------------------------------------- geometriya

    /** Dona turi: 0..n-1 filtr, n=compose, n+1=add. */
    private int itemType(int slot) {
        if (slot < filters.size()) {
            return ITEM_FILTER;
        }
        if (showCompose && slot == filters.size()) {
            return ITEM_COMPOSE;
        }
        if (addTile) {
            return ITEM_ADD;
        }
        return -1;
    }

    private int slotCount() {
        int n = filters.size();
        if (showCompose) {
            n++;
        }
        if (addTile) {
            n++;
        }
        return n;
    }

    /**
     * Yarim oy ichidagi konsentrik yoy: d=0 — yoyning eng chap nuqtasi;
     * |d| ortsa dona yarim oy qirrasi bo'ylab tepaga/pastga suriladi.
     */
    private boolean itemCenter(int slot, float[] out) {
        float d = slot - pos;
        float ad = Math.abs(d);
        if (ad > CULL_SLOTS) {
            return false;
        }
        float f = ad / CULL_SLOTS;
        float th = d * ARC_STEP;
        out[0] = getWidth() - dp(ARC_R_DP) * (float) Math.cos(th);
        out[1] = dp(DISC_CY_DP) + dp(ARC_R_DP) * (float) Math.sin(th);
        out[2] = 1f - 0.28f * f;                 // masshtab
        out[3] = 1f - 0.85f * f;                 // shaffoflik
        return true;
    }

    private float smallComposeCx() {
        return getWidth() - dp(SMALL_COMPOSE_FROM_EDGE_DP);
    }

    private float smallComposeCy() {
        return dp(DISC_CY_DP);
    }

    private float bigComposeCx() {
        return getWidth() - dp(BIG_COMPOSE_FROM_EDGE_DP);
    }

    // ------------------------------------------------------- chizish

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec) : (int) dp(WIDTH_DP);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int h = hMode == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : (int) dp(HEIGHT_DP);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        canvas.save();
        if (menuScale != 1f) {
            canvas.scale(menuScale, menuScale, getWidth(), dp(DISC_CY_DP));
        }
        if (topBlend < 1f) {
            int layer = topBlend > 0f
                    ? canvas.saveLayerAlpha(null, (int) (255 * (1f - topBlend))) : -1;
            drawPanel(canvas);
            int n = slotCount();
            float[] p = new float[4];
            // Chetdakilardan markazga: fokus eng ustida chizilsin.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    if (!itemCenter(i, p)) {
                        continue;
                    }
                    boolean focus = Math.abs(i - pos) < 0.5f;
                    if ((pass == 0) == focus) {
                        continue;
                    }
                    drawSlot(canvas, i, p[0], p[1], p[2], p[3], focus);
                }
            }
            if (layer >= 0) {
                canvas.restoreToCount(layer);
            }
        }
        if (topBlend > 0f) {
            drawBigCompose(canvas, topBlend);
        }
        if (topBlend < 1f) {
            drawSmallCompose(canvas, 1f - topBlend);
        }
        canvas.restore();
    }

    /**
     * Yarim oy korpusi: markazi ekran chetida bo'lgan yarim disk — tekis
     * tomoni o'ng chetga yopishgan, qavariq tomoni chatlar ustiga chiqadi.
     * Ichi siyoh-binafsha shisha, qirrasi binafsha nur, tepa-chapda oq sheen.
     */
    private void drawPanel(Canvas canvas) {
        float cx = getWidth();
        float cy = dp(DISC_CY_DP);
        float r = dp(DISC_R_DP);

        float key = getWidth() * 1000f + getHeight();
        if (panelShader == null || panelShaderKey != key) {
            panelShader = new LinearGradient(cx - r, cy - r, cx - r * 0.15f, cy + r,
                    new int[]{PANEL_TOP, PANEL_MID, PANEL_BOTTOM},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
            rimShader = new LinearGradient(cx - r, cy - r, cx - r * 0.2f, cy + r,
                    new int[]{RIM_BRIGHT, RIM, 0x59A98CFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            sheenShader = new RadialGradient(cx - r * 0.66f, cy - r * 0.58f, r * 1.2f,
                    new int[]{SHEEN, 0x14C0A8FF, 0x00C0A8FF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            panelShaderKey = key;
        }

        path.reset();
        rect.set(cx - r, cy - r, cx + r, cy + r);
        path.addArc(rect, 90f, 180f);

        // tashqi yumshoq nur — faqat yoy bo'ylab (tekis tomon chizilmaydi)
        fillPaint.setShader(null);
        fillPaint.setColor(GLOW_OUT);
        strokePaint.setShader(null);
        strokePaint.setColor(GLOW_OUT);
        strokePaint.setStrokeWidth(dp(9f));
        canvas.drawPath(path, strokePaint);

        // korpus + sheen
        path.close();
        fillPaint.setShader(panelShader);
        canvas.drawPath(path, fillPaint);
        fillPaint.setShader(sheenShader);
        canvas.drawPath(path, fillPaint);
        fillPaint.setShader(null);

        // qirra — yana faqat yoy
        rect.set(cx - r + dp(0.8f), cy - r + dp(0.8f), cx + r - dp(0.8f), cy + r - dp(0.8f));
        path.reset();
        path.addArc(rect, 90f, 180f);
        strokePaint.setShader(rimShader);
        strokePaint.setStrokeWidth(dp(1.6f));
        canvas.drawPath(path, strokePaint);

        // ichki oq bevel — tepa-chap yelkada shisha yorug'ligi
        rect.set(cx - r + dp(3.4f), cy - r + dp(3.4f), cx + r - dp(3.4f), cy + r - dp(3.4f));
        path.reset();
        path.addArc(rect, 196f, 74f);
        strokePaint.setShader(null);
        strokePaint.setColor(0x4DFFFFFF);
        strokePaint.setStrokeWidth(dp(1.1f));
        canvas.drawPath(path, strokePaint);

        // pastki yelkada aksent aksi
        path.reset();
        path.addArc(rect, 96f, 78f);
        strokePaint.setColor(0x26852AEE);
        canvas.drawPath(path, strokePaint);

        strokePaint.setStrokeWidth(dp(1.4f));
        fillPaint.setAlpha(255);
    }

    /**
     * Tekis tomon markazidagi asosiy qalamcha — yangi xabar. Brend gradientida,
     * atrofida yumshoq binafsha nur; yarim oy shishasi ustida suzib turadi.
     */
    private void drawSmallCompose(Canvas canvas, float alpha) {
        float cx = smallComposeCx();
        float cy = smallComposeCy();
        float r = dp(SMALL_COMPOSE_R_DP) * composeScale;
        int a = (int) (255 * alpha);
        if (smallShader == null || smallShaderCx != cx || smallShaderCy != cy) {
            smallShader = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    new int[]{ACTIVE_START, ACTIVE_END}, null, Shader.TileMode.CLAMP);
            smallShaderCx = cx;
            smallShaderCy = cy;
        }
        // yumshoq nur
        strokePaint.setShader(null);
        strokePaint.setColor(0x47852AEE);
        strokePaint.setStrokeWidth(dp(5f));
        strokePaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r + dp(1.2f), strokePaint);
        strokePaint.setAlpha(255);
        strokePaint.setStrokeWidth(dp(1.4f));

        fillPaint.setShader(smallShader);
        fillPaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r, fillPaint);
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);

        // shisha qavariqligi
        strokePaint.setColor(0x8CFFFFFF);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r - dp(0.6f), strokePaint);
        strokePaint.setAlpha(255);
        strokePaint.setStrokeWidth(dp(1.4f));

        iconPaint.setColor(ICON_ACTIVE);
        iconPaint.setAlpha(a);
        iconPaint.setStrokeWidth(dp(2f));
        drawPencil(canvas, cx, cy, r * 1.30f);
        iconPaint.setAlpha(255);
    }

    /** Top-tab rejimida yarim oy o'rnidagi katta qalamcha. */
    private void drawBigCompose(Canvas canvas, float alpha) {
        float cx = bigComposeCx();
        float cy = getHeight() / 2f;
        float r = dp(BIG_COMPOSE_R_DP) * composeScale;
        int a = (int) (255 * alpha);
        if (bigShader == null || bigShaderCx != cx || bigShaderCy != cy) {
            bigShader = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    new int[]{ACTIVE_START, ACTIVE_END}, null, Shader.TileMode.CLAMP);
            bigShaderCx = cx;
            bigShaderCy = cy;
        }
        strokePaint.setShader(null);
        strokePaint.setColor(0x33852AEE);
        strokePaint.setStrokeWidth(dp(7f));
        strokePaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r + dp(1.6f), strokePaint);
        strokePaint.setAlpha(255);
        strokePaint.setStrokeWidth(dp(1.4f));

        fillPaint.setShader(bigShader);
        fillPaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r, fillPaint);
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);

        strokePaint.setColor(0xB3FFFFFF);
        strokePaint.setStrokeWidth(dp(1.2f));
        strokePaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r - dp(0.6f), strokePaint);
        strokePaint.setAlpha(255);
        strokePaint.setStrokeWidth(dp(1.4f));

        iconPaint.setColor(ICON_ACTIVE);
        iconPaint.setAlpha(a);
        iconPaint.setStrokeWidth(dp(2.2f));
        drawPencil(canvas, cx, cy, r * 1.26f);
        iconPaint.setAlpha(255);
        iconPaint.setStrokeWidth(dp(2f));
    }

    private void drawSlot(Canvas canvas, int slot, float x, float y, float scale,
                          float alpha, boolean focus) {
        int type = itemType(slot);
        if (type < 0) {
            return;
        }
        if (y < -dp(CIRCLE_DP) || y > getHeight() + dp(CIRCLE_DP)) {
            return;
        }
        MessagesController.DialogFilter filter =
                type == ITEM_FILTER ? filters.get(slot) : null;
        float r = dp(CIRCLE_DP) / 2f * scale * (focus ? 1.34f : 1f);
        if (focus) {
            r *= 1f + 0.10f * (1f - pulseT);
        }

        canvas.save();
        rect.set(x - r - dp(28f), y - r - dp(28f), x + r + dp(28f), y + r + dp(28f));
        canvas.saveLayerAlpha(rect, (int) (255 * alpha));

        if (focus) {
            if (focusGlow == null || glowCx != x || glowCy != y || glowR != r) {
                focusGlow = new RadialGradient(x, y, r + dp(13f),
                        new int[]{0x7A852AEE, 0x33852AEE, 0x00852AEE},
                        new float[]{0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP);
                glowCx = x;
                glowCy = y;
                glowR = r;
            }
            fillPaint.setShader(focusGlow);
            canvas.drawCircle(x, y, r + dp(13f), fillPaint);

            if (activeShader == null || shaderCx != x || shaderCy != y || shaderR != r) {
                activeShader = new LinearGradient(x - r, y - r, x + r, y + r,
                        new int[]{ACTIVE_START, ACTIVE_END}, null, Shader.TileMode.CLAMP);
                shaderCx = x;
                shaderCy = y;
                shaderR = r;
            }
            fillPaint.setShader(activeShader);
            canvas.drawCircle(x, y, r, fillPaint);
            fillPaint.setShader(null);

            // shisha qavariqligi + tashqi aksent halqa
            strokePaint.setShader(null);
            strokePaint.setColor(0x59FFFFFF);
            strokePaint.setStrokeWidth(dp(1.1f));
            canvas.drawCircle(x, y, r - dp(0.6f), strokePaint);
            strokePaint.setColor(BORDER);
            strokePaint.setStrokeWidth(dp(1.4f));
            canvas.drawCircle(x, y, r + dp(2.2f), strokePaint);

            // puls halqasi — tanlanganda bir marta kengayib yo'qoladi
            if (pulseT < 1f) {
                strokePaint.setColor(GLOW);
                strokePaint.setAlpha((int) (190 * (1f - pulseT)));
                strokePaint.setStrokeWidth(dp(2f));
                canvas.drawCircle(x, y, r + dp(3f) + dp(11f) * pulseT, strokePaint);
                strokePaint.setAlpha(255);
                strokePaint.setStrokeWidth(dp(1.4f));
            }
            iconPaint.setColor(ICON_ACTIVE);
        } else {
            fillPaint.setColor(INACTIVE_FILL);
            canvas.drawCircle(x, y, r, fillPaint);
            strokePaint.setColor(INACTIVE_STROKE);
            strokePaint.setStrokeWidth(dp(1.2f));
            canvas.drawCircle(x, y, r, strokePaint);
            strokePaint.setStrokeWidth(dp(1.4f));
            iconPaint.setColor(ICON_IDLE);
        }

        float glyph = r * 1.05f;
        if (type == ITEM_COMPOSE) {
            drawPencil(canvas, x, y, glyph);
        } else if (type == ITEM_ADD) {
            drawPlus(canvas, x, y, glyph);
        } else {
            drawGlyph(canvas, glyphType(filter, slot), x, y, glyph);
        }

        int counter = 0;
        if (type == ITEM_FILTER && counterProvider != null) {
            counter = Math.max(0, counterProvider.getCounter(filter));
        }
        if (filter != null && filter.locked) {
            fillPaint.setColor(0xFFFFC24B);
            canvas.drawCircle(x - r * 0.72f, y - r * 0.72f, dp(2.6f), fillPaint);
        }
        // Son — donaning past-o'ngida: nom chapda, to'qnashmaydi.
        if (counter > 0 && alpha >= 0.25f) {
            drawBadge(canvas, counter, x + r * 0.70f, y + r * 0.70f, alpha, focus);
        }
        canvas.restore();
        canvas.restore();

        drawCaption(canvas, labelForSlot(type, filter), x, y, r, alpha, focus);
    }

    private String labelForSlot(int type, MessagesController.DialogFilter filter) {
        if (type == ITEM_FILTER) {
            return labelFor(filter);
        }
        return type == ITEM_COMPOSE
                ? getContext().getString(R.string.yuz_wheel_new_msg)
                : LocaleController.getString("Add", R.string.Add);
    }

    private void drawBadge(Canvas canvas, int counter, float cx, float cy,
                           float alpha, boolean focus) {
        String count = counter > 99 ? "99+" : String.valueOf(counter);
        countPaint.setTextSize(focus ? dp(9f) : dp(8f));
        float rr = Math.max(dp(8.5f), countPaint.measureText(count) / 2f + dp(3.5f));
        countPaint.setAlpha((int) (255 * alpha));
        fillPaint.setColor(COUNT_BG);
        fillPaint.setAlpha((int) (245 * alpha));
        canvas.drawCircle(cx, cy, rr, fillPaint);
        fillPaint.setAlpha(255);
        Paint.FontMetrics cfm = countPaint.getFontMetrics();
        canvas.drawText(count, cx, cy - (cfm.ascent + cfm.descent) / 2f, countPaint);
        countPaint.setAlpha(255);
    }

    /**
     * Donaning CHAP tomonidagi nom — fokus nomi shisha pill ichida oq,
     * qolganlari xira va kichikroq. Pill faqat fokusda, shunda menyu tinch.
     */
    private void drawCaption(Canvas canvas, String label, float x, float y, float r,
                             float alpha, boolean focus) {
        if (label == null || label.isEmpty() || alpha < 0.25f) {
            return;
        }
        float size = focus ? labelBaseSize : labelBaseSize * 0.84f;
        textPaint.setTextSize(size);
        textPaint.setColor(focus ? LABEL_FOCUS : LABEL_IDLE);
        textPaint.setAlpha((int) (255 * alpha));
        float right = x - r - dp(9f);
        float maxText = right - dp(6f);
        float floor = size * 0.78f;
        while (size > floor && textPaint.measureText(label) > maxText) {
            size = Math.max(floor, size - dp(0.4f));
            textPaint.setTextSize(size);
        }
        if (textPaint.measureText(label) > maxText) {
            label = fitTail(label, maxText);
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float tw = textPaint.measureText(label);
        if (focus) {
            float ph = -fm.ascent + fm.descent + dp(9f);
            rect.set(right - tw - dp(9f), y - ph / 2f, right + dp(9f), y + ph / 2f);
            fillPaint.setShader(null);
            fillPaint.setColor(0xE61C0F38);
            canvas.drawRoundRect(rect, ph / 2f, ph / 2f, fillPaint);
            strokePaint.setShader(null);
            strokePaint.setColor(0x8CA98CFF);
            strokePaint.setStrokeWidth(dp(1f));
            canvas.drawRoundRect(rect, ph / 2f, ph / 2f, strokePaint);
            strokePaint.setStrokeWidth(dp(1.4f));
        }
        canvas.drawText(label, right, y - (fm.ascent + fm.descent) / 2f, textPaint);
        textPaint.setAlpha(255);
        textPaint.setTextSize(labelBaseSize);
    }

    /** Matnni maxW kengligiga sig'adigan qismga tushiradi va "…" bilan tugatadi. */
    private String fitTail(String text, float maxW) {
        int end = text.length();
        while (end > 1 && textPaint.measureText(text.substring(0, end) + "…") > maxW) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private String labelFor(MessagesController.DialogFilter filter) {
        if (filter == null) {
            return "";
        }
        if (filter.isDefault()) {
            return LocaleController.getString("FilterAllChats", R.string.FilterAllChats);
        }
        return filter.name == null ? "" : filter.name;
    }

    private int glyphType(MessagesController.DialogFilter filter, int index) {
        if (filter == null) {
            return GLYPH_CHAT;
        }
        if (filter.isChatlist()) {
            return GLYPH_LIST;
        }
        int f = filter.flags;
        boolean onlyArchived = (f & MessagesController.DIALOG_FILTER_FLAG_ONLY_ARCHIVED) != 0;
        if (onlyArchived) {
            return GLYPH_ARCHIVE;
        }
        boolean contacts = (f & MessagesController.DIALOG_FILTER_FLAG_CONTACTS) != 0;
        boolean nonContacts = (f & MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0;
        boolean groups = (f & MessagesController.DIALOG_FILTER_FLAG_GROUPS) != 0;
        boolean channels = (f & MessagesController.DIALOG_FILTER_FLAG_CHANNELS) != 0;
        boolean bots = (f & MessagesController.DIALOG_FILTER_FLAG_BOTS) != 0;
        if (filter.isDefault() || (contacts && nonContacts && groups && channels && bots)) {
            return GLYPH_CHAT;
        }
        if (bots && !contacts && !groups && !channels) {
            return GLYPH_BOT;
        }
        if (contacts && !nonContacts && !groups && !channels && !bots) {
            return GLYPH_USER;
        }
        if (channels && !groups && !contacts) {
            return GLYPH_CHANNEL;
        }
        if (groups && !channels && !contacts) {
            return GLYPH_GROUP;
        }
        if (groups && channels && !contacts && !nonContacts) {
            return GLYPH_GROUP;
        }
        return index % 2 == 0 ? GLYPH_CHAT : GLYPH_LIST;
    }

    private static final int GLYPH_CHAT = 0, GLYPH_USER = 1, GLYPH_GROUP = 2, GLYPH_CHANNEL = 3,
            GLYPH_BOT = 4, GLYPH_ARCHIVE = 5, GLYPH_LIST = 6;

    private void drawPlus(Canvas canvas, float cx, float cy, float size) {
        float s = size / 2f;
        canvas.drawLine(cx - s * 0.85f, cy, cx + s * 0.85f, cy, iconPaint);
        canvas.drawLine(cx, cy - s * 0.85f, cx, cy + s * 0.85f, iconPaint);
    }

    /**
     * Professional qalamcha: 45° qiyshaygan silindr korpus, to'q grafit uchi va
     * o'chirg'ich bandi. Vektor — har qanday o'lchamda aniq chiziladi.
     */
    private void drawPencil(Canvas canvas, float cx, float cy, float size) {
        float l = size * 0.5f;
        int a = iconPaint.getAlpha();
        canvas.save();
        canvas.rotate(-45f, cx, cy);

        // korpus
        rect.set(cx - l * 0.30f, cy - l * 0.26f, cx + l * 0.94f, cy + l * 0.26f);
        fillPaint.setShader(null);
        fillPaint.setColor(iconPaint.getColor());
        fillPaint.setAlpha(a);
        canvas.drawRoundRect(rect, l * 0.10f, l * 0.10f, fillPaint);

        // uchi — grafit konus
        path.reset();
        path.moveTo(cx - l, cy);
        path.lineTo(cx - l * 0.30f, cy - l * 0.26f);
        path.lineTo(cx - l * 0.30f, cy + l * 0.26f);
        path.close();
        fillPaint.setColor(PENCIL_TIP);
        fillPaint.setAlpha((int) (a * 0.82f));
        canvas.drawPath(path, fillPaint);

        // o'chirg'ich bandi
        rect.set(cx + l * 0.60f, cy - l * 0.26f, cx + l * 0.94f, cy + l * 0.26f);
        fillPaint.setColor(PENCIL_TIP);
        fillPaint.setAlpha((int) (a * 0.30f));
        canvas.drawRoundRect(rect, l * 0.10f, l * 0.10f, fillPaint);

        fillPaint.setAlpha(255);
        canvas.restore();
    }

    private void drawGlyph(Canvas canvas, int type, float cx, float cy, float size) {
        float s = size / 2f;
        switch (type) {
            case GLYPH_USER: {
                canvas.drawCircle(cx, cy - s * 0.42f, s * 0.42f, iconPaint);
                path.reset();
                path.addArc(cx - s * 0.78f, cy + s * 0.12f, cx + s * 0.78f, cy + s * 1.1f, 195, 150);
                canvas.drawPath(path, iconPaint);
                break;
            }
            case GLYPH_GROUP: {
                canvas.drawCircle(cx - s * 0.42f, cy - s * 0.34f, s * 0.34f, iconPaint);
                canvas.drawCircle(cx + s * 0.5f, cy - s * 0.14f, s * 0.26f, iconPaint);
                path.reset();
                path.addArc(cx - s * 1.15f, cy + s * 0.2f, cx + s * 0.3f, cy + s * 1.15f, 195, 150);
                canvas.drawPath(path, iconPaint);
                path.reset();
                path.addArc(cx + s * 0.1f, cy + s * 0.36f, cx + s * 1.15f, cy + s * 1.2f, 200, 140);
                canvas.drawPath(path, iconPaint);
                break;
            }
            case GLYPH_CHANNEL: {
                path.reset();
                path.moveTo(cx - s * 0.85f, cy - s * 0.18f);
                path.lineTo(cx + s * 0.18f, cy - s * 0.75f);
                path.lineTo(cx + s * 0.18f, cy + s * 0.6f);
                path.lineTo(cx - s * 0.85f, cy + s * 0.18f);
                path.close();
                canvas.drawPath(path, iconPaint);
                canvas.drawLine(cx + s * 0.42f, cy - s * 0.5f, cx + s * 0.78f, cy - s * 0.62f, iconPaint);
                canvas.drawLine(cx + s * 0.45f, cy - s * 0.05f, cx + s * 0.85f, cy - s * 0.05f, iconPaint);
                canvas.drawLine(cx + s * 0.42f, cy + s * 0.4f, cx + s * 0.78f, cy + s * 0.52f, iconPaint);
                break;
            }
            case GLYPH_BOT: {
                rect.set(cx - s * 0.8f, cy - s * 0.45f, cx + s * 0.8f, cy + s * 0.75f);
                canvas.drawRoundRect(rect, s * 0.34f, s * 0.34f, iconPaint);
                canvas.drawLine(cx, cy - s * 0.45f, cx, cy - s * 0.85f, iconPaint);
                canvas.drawCircle(cx, cy - s * 0.95f, s * 0.13f, iconPaint);
                canvas.drawCircle(cx - s * 0.34f, cy + s * 0.08f, s * 0.1f, iconPaint);
                canvas.drawCircle(cx + s * 0.34f, cy + s * 0.08f, s * 0.1f, iconPaint);
                canvas.drawLine(cx - s * 0.22f, cy + s * 0.44f, cx + s * 0.22f, cy + s * 0.44f, iconPaint);
                break;
            }
            case GLYPH_ARCHIVE: {
                rect.set(cx - s * 0.9f, cy - s * 0.62f, cx + s * 0.9f, cy - s * 0.2f);
                canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, iconPaint);
                path.reset();
                path.moveTo(cx - s * 0.74f, cy - s * 0.2f);
                path.lineTo(cx - s * 0.6f, cy + s * 0.8f);
                path.lineTo(cx + s * 0.6f, cy + s * 0.8f);
                path.lineTo(cx + s * 0.74f, cy - s * 0.2f);
                canvas.drawPath(path, iconPaint);
                canvas.drawLine(cx - s * 0.26f, cy + s * 0.16f, cx + s * 0.26f, cy + s * 0.16f, iconPaint);
                break;
            }
            case GLYPH_LIST: {
                for (int i = 0; i < 3; i++) {
                    float yy = cy - s * 0.55f + i * s * 0.55f;
                    canvas.drawLine(cx - s * 0.8f, yy, cx + s * 0.8f, yy, iconPaint);
                }
                break;
            }
            default: {
                rect.set(cx - s * 0.9f, cy - s * 0.8f, cx + s * 0.9f, cy + s * 0.55f);
                canvas.drawRoundRect(rect, s * 0.42f, s * 0.42f, iconPaint);
                path.reset();
                path.moveTo(cx - s * 0.42f, cy + s * 0.55f);
                path.lineTo(cx - s * 0.55f, cy + s * 0.95f);
                path.lineTo(cx + s * 0.05f, cy + s * 0.55f);
                canvas.drawPath(path, iconPaint);
                break;
            }
        }
    }

    // ------------------------------------------------------- animatsiyalar

    private void cancelSlide() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void animateTo(float target, boolean fling) {
        cancelSlide();
        float from = pos;
        if (Math.abs(target - from) < 0.001f) {
            pos = target;
            invalidate();
            return;
        }
        int dur = (int) Math.min(460, 170 + Math.abs(target - from) * 120);
        animator = ValueAnimator.ofFloat(from, target);
        animator.setDuration(dur);
        animator.setInterpolator(fling ? new OvershootInterpolator(1.0f)
                : new OvershootInterpolator(0.7f));
        animator.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                settle(true);
            }
        });
        animator.start();
    }

    /** Pozitsiya to'xtaganda: fokus filtrini tanlash yoki amalni bajarish. */
    private void settle(boolean notify) {
        int idx = Math.round(pos);
        if (idx < 0) {
            idx = 0;
        }
        int last = Math.max(0, filters.size() - 1);
        if (idx > last) {
            // Aksiya donasiga shakllanib qolsa — filtrga qaytaramiz.
            idx = last;
        }
        if (Math.abs(pos - idx) > 0.001f) {
            animateTo(idx, false);
            return;
        }
        boolean changed = idx != selectedIndex;
        selectedIndex = idx;
        if (changed && notify) {
            tick();
            startPulse();
            if (listener != null) {
                listener.onFolderSelected(idx);
            }
        } else {
            invalidate();
        }
    }

    private void tick() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void startPulse() {
        if (pulse != null) {
            pulse.cancel();
        }
        pulseT = 0f;
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(340);
        pulse.addUpdateListener(a -> {
            pulseT = (float) a.getAnimatedValue();
            invalidate();
        });
        pulse.start();
    }

    /**
     * Yarim oyning umumiy masshtabi: barmoq tegsa sal kattalashadi, surishda
     * yanada o'sadi, qo'yib yuborilganda prujina bilan ixchamlashadi.
     */
    private void animateScale(float target, int duration, boolean springy) {
        if (scaleAnim != null) {
            scaleAnim.cancel();
            scaleAnim = null;
        }
        if (Math.abs(menuScale - target) < 0.001f) {
            menuScale = target;
            invalidate();
            return;
        }
        scaleAnim = ValueAnimator.ofFloat(menuScale, target);
        scaleAnim.setDuration(duration);
        scaleAnim.setInterpolator(springy
                ? new OvershootInterpolator(1.6f)
                : new DecelerateInterpolator(1.6f));
        scaleAnim.addUpdateListener(a -> {
            menuScale = (float) a.getAnimatedValue();
            invalidate();
        });
        scaleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                scaleAnim = null;
            }
        });
        scaleAnim.start();
    }

    /** Qalamchaning o'z masshtabi — bosilganda cho'kadi, qo'yilganda sakraydi. */
    private void animateComposeScale(float target, int duration, boolean springy) {
        if (composeAnim != null) {
            composeAnim.cancel();
            composeAnim = null;
        }
        if (Math.abs(composeScale - target) < 0.001f) {
            composeScale = target;
            invalidate();
            return;
        }
        composeAnim = ValueAnimator.ofFloat(composeScale, target);
        composeAnim.setDuration(duration);
        composeAnim.setInterpolator(springy
                ? new OvershootInterpolator(2.2f)
                : new DecelerateInterpolator(1.8f));
        composeAnim.addUpdateListener(a -> {
            composeScale = (float) a.getAnimatedValue();
            invalidate();
        });
        composeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                composeAnim = null;
            }
        });
        composeAnim.start();
    }

    /** Ekran koordinatasini masshtabdan tashqari (chizma) koordinataga aylantiradi. */
    private float unscaledX(float x) {
        if (menuScale == 1f) {
            return x;
        }
        return getWidth() + (x - getWidth()) / menuScale;
    }

    private float unscaledY(float y) {
        if (menuScale == 1f) {
            return y;
        }
        float cy = dp(DISC_CY_DP);
        return cy + (y - cy) / menuScale;
    }

    private void shake() {
        animate().cancel();
        setTranslationX(0);
        animate().translationX(dp(5)).setDuration(50)
                .withEndAction(() -> animate().translationX(-dp(5)).setDuration(60)
                        .withEndAction(() -> animate().translationX(0).setDuration(60).start())
                        .start())
                .start();
    }

    // --------------------------------------------------------- sensorlar

    private int slotAt(float x, float y) {
        float[] p = new float[4];
        int n = slotCount();
        for (int i = 0; i < n; i++) {
            if (!itemCenter(i, p)) {
                continue;
            }
            boolean focus = Math.abs(i - pos) < 0.5f;
            float r = dp(CIRCLE_DP) / 2f * p[2] * (focus ? 1.34f : 1f) + dp(10f);
            float dx = x - p[0], dy = y - p[1];
            if (dx * dx + dy * dy <= r * r) {
                return i;
            }
        }
        return -1;
    }

    /** Bir dona vertikal oralig'i (px) — surish koeffitsienti. */
    private float spacingPx() {
        return dp(ARC_R_DP) * ARC_STEP;
    }

    /** Yarim oy tanasi ichidami? Tashqarisidagi teginishlar chatga o'tadi. */
    private boolean insidePanel(float x, float y) {
        float dx = x - getWidth();
        float dy = y - dp(DISC_CY_DP);
        float rr = dp(DISC_R_DP) - dp(3f);
        return dx * dx + dy * dy <= rr * rr;
    }

    private boolean composeHit(float x, float y) {
        float cx;
        float cy;
        float rr;
        if (topTabsMode) {
            cx = bigComposeCx();
            cy = getHeight() / 2f;
            rr = dp(BIG_COMPOSE_R_DP) + dp(12f);
        } else {
            cx = smallComposeCx();
            cy = smallComposeCy();
            rr = dp(SMALL_COMPOSE_R_DP) + dp(10f);
        }
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= rr * rr;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float ex = unscaledX(event.getX());
        final float ey = unscaledY(event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (composeHit(ex, ey)) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    cancelSlide();
                    composePressed = true;
                    composeTracking = true;
                    composeDownX = ex;
                    composeDownY = ey;
                    animateComposeScale(0.90f, 90, false);
                    return true;
                }
                if (topTabsMode || slotCount() == 0) {
                    return false;
                }
                // Yarim oy TASHQARISidagi urishlar o'tib ketadi: ostdagi
                // chat qatorlari to'liq kenglikda bosiladigan bo'lib qoladi.
                if (!insidePanel(ex, ey)) {
                    return false;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                cancelSlide();
                downY = ey;
                lastY = ey;
                lastT = event.getEventTime();
                downPos = pos;
                velocity = 0f;
                dragging = false;
                pressItem = slotAt(ex, ey);
                // Barmoq tegdikda menyu tirik javob beradi.
                animateScale(1.04f, 140, false);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (composeTracking) {
                    float cdx = ex - composeDownX, cdy = ey - composeDownY;
                    if (composePressed && cdx * cdx + cdy * cdy > dp(12f) * dp(12f)) {
                        composePressed = false;
                        animateComposeScale(1f, 160, true);
                    }
                    return true;
                }
                if (topTabsMode) {
                    return false;
                }
                float y = ey;
                float t = event.getEventTime();
                float dt = Math.max(1f, t - lastT);
                float v = (y - lastY) / dt * 1000f;
                velocity = velocity * 0.6f + v * 0.4f;
                lastY = y;
                lastT = t;
                float dy = y - downY;
                if (!dragging && Math.abs(dy) > dp(8f) && slotCount() > 1) {
                    dragging = true;
                    // Surish boshlandi — menyu kattalashadi.
                    animateScale(SCALE_DRAG, 200, false);
                }
                if (dragging) {
                    float want = downPos - dy / spacingPx();
                    // Rezinka: chegaradan ortga cho'zilsa qarshilik.
                    if (want < 0f) {
                        want = want * 0.35f;
                    } else if (want > slotCount() - 1) {
                        want = (slotCount() - 1) + (want - (slotCount() - 1)) * 0.35f;
                    }
                    pos = want;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (composeTracking) {
                    boolean fire = composePressed
                            && event.getActionMasked() == MotionEvent.ACTION_UP
                            && composeHit(ex, ey);
                    composeTracking = false;
                    composePressed = false;
                    animateComposeScale(1f, 240, true);
                    if (fire) {
                        tick();
                        if (actionListener != null) {
                            actionListener.onActionClicked(ACTION_COMPOSE);
                        }
                    }
                    return true;
                }
                if (topTabsMode) {
                    return false;
                }
                boolean wasDragging = dragging;
                boolean tap = !dragging && event.getActionMasked() == MotionEvent.ACTION_UP;
                dragging = false;
                // Tanlovdan keyin yarim oy yana ixchamlashadi.
                animateScale(1f, 300, true);
                if (tap && pressItem >= 0) {
                    int tapped = pressItem;
                    pressItem = -1;
                    int type = itemType(tapped);
                    if (type == ITEM_COMPOSE) {
                        tick();
                        if (actionListener != null) {
                            actionListener.onActionClicked(ACTION_COMPOSE);
                        }
                        settle(false);
                        return true;
                    }
                    if (type == ITEM_ADD) {
                        if (addFolderAction != null) {
                            addFolderAction.run();
                        }
                        settle(false);
                        return true;
                    }
                    MessagesController.DialogFilter f = filters.get(tapped);
                    if (f != null && f.locked && Math.abs(pos - tapped) > 0.25f) {
                        shake();
                        return true;
                    }
                    animateTo(tapped, true);
                    return true;
                }
                if (wasDragging && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float target = pos - velocity * 0.22f / spacingPx();
                    int last = Math.max(0, filters.size() - 1);
                    if (target < 0f) {
                        target = 0f;
                    } else if (target > last) {
                        target = last;
                    } else {
                        target = Math.round(target);
                    }
                    animateTo(target, true);
                } else {
                    settle(true);
                }
                pressItem = -1;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        if (pulse != null) {
            pulse.cancel();
        }
        if (scaleAnim != null) {
            scaleAnim.cancel();
            scaleAnim = null;
        }
        if (composeAnim != null) {
            composeAnim.cancel();
            composeAnim = null;
        }
        if (topBlendAnim != null) {
            topBlendAnim.cancel();
            topBlendAnim = null;
        }
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
