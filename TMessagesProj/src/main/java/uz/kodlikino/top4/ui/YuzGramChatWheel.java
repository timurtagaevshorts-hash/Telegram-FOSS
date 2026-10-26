package uz.kodlikino.top4.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.ArrayList;

/**
 * YuzGram #193b: chat ro'yxati ustidagi filtr ustuni — PASTKI-O'NG burchakda
 * ixcham vertikal shisha "tayoqcha" (balandligi 184dp, kengligi 104dp).
 *
 * Oldingi dumaloq gumbaz fan bo'lib yoyilardi va har bir dona yonida nom
 * ko'tarib turardi — joy egallardi. Endi: donalar deyarli TIK ustun bo'ylab
 * (katta radiusli, 10dp'lik mayin egilish) teriladi, nom faqat fokusdagi
 * donaning CHAP tomoniga chiqadigan shisha qanotda ko'rinadi. Shu bilan
 * ustun ixchamlashdi va chat qatorlarining o'ng chekkasi ochildi.
 *
 * Barmoq vertikal suriladi; qo'yib yuborilganda inertsiya bilan eng yaqin
 * tugunga qonadi. Sensor maydoni endi faqat kapsula ustuni — undan chapdagi
 * chat qatorlari bosilishini to'smaydi.
 *
 * Kontrakt o'zgarmaydi: setFilters/setListener/setCounterProvider/
 * setActionListener/setOnAddFolder/setActions/setSelectedIndex. Joylash:
 * Gravity.RIGHT|BOTTOM, o'lchov statik width()/height().
 */
public class YuzGramChatWheel extends View {

    /** Ustun kengligi — ko'rinadigan kapsula 36dp, qolgani yorliq uchun. */
    public static final int WIDTH_DP = 116;
    /** Ustun balandligi. */
    public static final int HEIGHT_DP = 184;

    /** Kapsula o'qi — o'ng chekkadan shu masofada. */
    private static final float SPINE_INSET_DP = 22f;
    private static final float CAPSULE_W_DP = 36f;
    /** Kapsulaning tepa/past chekinishi va bel qismidagi qo'shimcha kenglik. */
    private static final float CAPSULE_TOP_DP = 4f;
    private static final float CAPSULE_WAIST_DP = 3f;
    private static final float NODE_R_DP = 12f;
    private static final float FOCUS_R_DP = 15f;
    /** Donalar orasidagi tik qadam. */
    private static final float SPACING_DP = 30f;
    /** Yoy radiusi katta — ustun tik bo'lib qoladi, chetlari sal egiladi. */
    private static final float CURVE_R_DP = 178f;
    private static final float CURVE_STEP = SPACING_DP / CURVE_R_DP;
    private static final float CURVE_BOW_DP = 72f;
    /** Fokusdan necha dona uzoqda dona butunlay yo'qoladi. */
    private static final float CULL_SLOTS = 3.2f;

    private static final int BORDER = 0x592AABEE;
    private static final int GLOW = 0x662AABEE;
    private static final int ACTIVE_START = 0xFF38B6FF;
    private static final int ACTIVE_END = 0xFF5B6CF9;
    private static final int INACTIVE_FILL = 0x4D2A4570;
    private static final int INACTIVE_STROKE = 0x7338B6FF;
    private static final int ICON_ACTIVE = 0xFFFFFFFF;
    private static final int ICON_IDLE = 0xFFBFD0EA;
    /** Fokus yorlig'i: shisha qanot ichidagi oq matn. */
    private static final int WING_TEXT = 0xFFFFFFFF;
    /** O'qilmaganlar: fokusda raqam, boshqalarda nuqta — ixcham ierarxiya. */
    private static final int COUNT_BG = 0xFF2AABEE;
    private static final int COUNT_TEXT = 0xFF061424;

    private static final int CAPSULE_TOP = 0x261F3E66;
    private static final int CAPSULE_BOTTOM = 0x1A142E4A;
    private static final int CAPSULE_EDGE = 0x3D8FC4F0;
    private static final int CAPSULE_HALO = 0x1038B6FF;
    private static final int WING_FILL = 0x33FFFFFF;
    private static final int WING_EDGE = 0x4D8FC4F0;
    private static final int WING_LINK = 0x668FC4F0;

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
    private final Path capsule = new Path();

    private ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
    private Listener listener;
    private ActionListener actionListener;
    private CounterProvider counterProvider;
    private Runnable addFolderAction;
    private boolean showCompose = true;
    private boolean addTile;

    private float pos;           // ustun pozitsiyasi (dona birligida)
    private int selectedIndex = 0;

    private float downY, downPos;
    private float lastY, lastT;
    private float velocity;      // px/s
    private boolean dragging;
    private int pressItem = -1;  // bosilgan dona indeksi (-1 yo'q)
    private ValueAnimator animator;
    private ValueAnimator pulse;
    private float pulseT = 1f;

    private LinearGradient activeShader;
    private float shaderCx = Float.MIN_VALUE, shaderCy = Float.MIN_VALUE, shaderR = Float.MIN_VALUE;
    /** Fokus yorlig'i asosiy o'lchami (sp). */
    private float labelBaseSize;

    public YuzGramChatWheel(Context context) {
        super(context);
        setClickable(true);
        setWillNotDraw(false);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.2f));
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(2f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        labelBaseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f,
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
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
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
     * Dona markazi: o'q bo'ylab TIK siljiydi (y = R·sin), chetga qarab juda
     * sekin o'ngga egiladi (x = R·(1−cos)) — shisha kapsula ichida chuqurlik
     * hissi beradi, lekin ustun vertikalligini yo'qotmaydi.
     */
    private boolean itemCenter(int slot, float[] out) {
        float d = slot - pos;
        float ad = Math.abs(d);
        if (ad > CULL_SLOTS) {
            return false;
        }
        float th = d * CURVE_STEP;
        float cx = getWidth() - dp(SPINE_INSET_DP);
        float cy = getHeight() / 2f;
        out[0] = cx + dp(CURVE_BOW_DP) * (1f - (float) Math.cos(th));
        out[1] = cy + dp(CURVE_R_DP) * (float) Math.sin(th);
        float f = ad / CULL_SLOTS;
        out[2] = 1f - 0.22f * f;                 // masshtab
        out[3] = 1f - 0.80f * f;                 // shaffoflik
        return true;
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
        drawCapsule(canvas);
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
    }

    /**
     * Pastki-o'ngdagi vertikal shisha kapsula: beli sal kengroq, uchlari
     * yumaloq purosimon shakl (Path) — dumaloq gumbazdan farqli, tik va
     * ixcham. Ichki gradient tepada sal yorug', pastda brend ko'kiga og'adi.
     */
    private void drawCapsule(Canvas canvas) {
        float cx = getWidth() - dp(SPINE_INSET_DP);
        float top = dp(CAPSULE_TOP_DP);
        float bottom = getHeight() - dp(CAPSULE_TOP_DP);
        float halfEnd = dp(CAPSULE_W_DP) / 2f;
        float halfWaist = halfEnd + dp(CAPSULE_WAIST_DP);
        float rEnd = halfEnd;
        float midY = (top + bottom) / 2f;

        capsule.reset();
        capsule.moveTo(cx - halfEnd, top + rEnd);
        capsule.quadTo(cx - halfEnd, top, cx - halfEnd + rEnd, top);
        capsule.lineTo(cx + halfEnd - rEnd, top);
        capsule.quadTo(cx + halfEnd, top, cx + halfEnd, top + rEnd);
        capsule.quadTo(cx + halfWaist, midY, cx + halfEnd, bottom - rEnd);
        capsule.quadTo(cx + halfEnd, bottom, cx + halfEnd - rEnd, bottom);
        capsule.lineTo(cx - halfEnd + rEnd, bottom);
        capsule.quadTo(cx - halfEnd, bottom, cx - halfEnd, bottom - rEnd);
        capsule.quadTo(cx - halfWaist, midY, cx - halfEnd, top + rEnd);
        capsule.close();

        // Tashqi yumshoq nur avval — kapsula uning ustidan chiziladi.
        strokePaint.setColor(CAPSULE_HALO);
        strokePaint.setStrokeWidth(dp(7f));
        canvas.drawPath(capsule, strokePaint);

        fillPaint.setShader(new LinearGradient(cx, top, cx, bottom,
                new int[]{CAPSULE_TOP, CAPSULE_BOTTOM, CAPSULE_TOP},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(capsule, fillPaint);
        fillPaint.setShader(null);

        strokePaint.setColor(CAPSULE_EDGE);
        strokePaint.setStrokeWidth(dp(1.1f));
        canvas.drawPath(capsule, strokePaint);
        strokePaint.setStrokeWidth(dp(1.2f));
    }

    private void drawSlot(Canvas canvas, int slot, float x, float y, float scale,
                          float alpha, boolean focus) {
        int type = itemType(slot);
        if (type < 0) {
            return;
        }
        if (y < -dp(30) || y > getHeight() + dp(30)) {
            return;
        }
        MessagesController.DialogFilter filter =
                type == ITEM_FILTER ? filters.get(slot) : null;
        float r = (focus ? dp(FOCUS_R_DP) : dp(NODE_R_DP)) * scale;
        r *= focus ? (1f + 0.06f * (1f - pulseT)) : 1f;

        canvas.save();
        rect.set(x - r - dp(22), y - r - dp(22), x + r + dp(22), y + r + dp(22));
        canvas.saveLayerAlpha(rect, (int) (255 * alpha));

        if (focus) {
            if (activeShader == null || shaderCx != x || shaderCy != y || shaderR != r) {
                activeShader = new LinearGradient(x - r, y - r, x + r, y + r,
                        new int[]{ACTIVE_START, ACTIVE_END}, null, Shader.TileMode.CLAMP);
                shaderCx = x;
                shaderCy = y;
                shaderR = r;
            }
            fillPaint.setColor(0xFFFFFFFF);
            fillPaint.setShader(activeShader);
            canvas.drawCircle(x, y, r, fillPaint);
            fillPaint.setShader(null);
            // puls halqasi — tanlanganda bir marta kengayib yo'qoladi.
            if (pulseT < 1f) {
                strokePaint.setColor(GLOW);
                strokePaint.setAlpha((int) (200 * (1f - pulseT)));
                strokePaint.setStrokeWidth(dp(2f));
                canvas.drawCircle(x, y, r + dp(2f) + dp(9f) * pulseT, strokePaint);
                strokePaint.setAlpha(255);
                strokePaint.setStrokeWidth(dp(1.2f));
            }
            strokePaint.setColor(BORDER);
            strokePaint.setStrokeWidth(dp(1.6f));
            canvas.drawCircle(x, y, r + dp(3f), strokePaint);
            strokePaint.setStrokeWidth(dp(1.2f));
            iconPaint.setColor(ICON_ACTIVE);
        } else {
            fillPaint.setColor(INACTIVE_FILL);
            canvas.drawCircle(x, y, r, fillPaint);
            strokePaint.setColor(INACTIVE_STROKE);
            canvas.drawCircle(x, y, r, strokePaint);
            iconPaint.setColor(ICON_IDLE);
        }

        float glyph = r * 1.12f;
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
        // Son — donaning o'ng-ustida (yorliq chapda, to'sqinlik qilmaydi).
        if (counter > 0 && alpha >= 0.25f) {
            if (focus) {
                drawBadge(canvas, counter, x + r * 0.66f, y - r * 0.86f, alpha);
            } else {
                fillPaint.setColor(COUNT_BG);
                fillPaint.setAlpha((int) (235 * alpha));
                canvas.drawCircle(x + r * 0.72f, y - r * 0.72f, dp(2.4f), fillPaint);
                fillPaint.setAlpha(255);
            }
        }
        canvas.restore();
        canvas.restore();

        if (focus) {
            drawWing(canvas, labelForSlot(type, filter), x, y, r, alpha);
        }
    }

    private String labelForSlot(int type, MessagesController.DialogFilter filter) {
        if (type == ITEM_FILTER) {
            return labelFor(filter);
        }
        return type == ITEM_COMPOSE
                ? getContext().getString(R.string.yuz_wheel_new_msg)
                : LocaleController.getString("Add", R.string.Add);
    }

    private void drawBadge(Canvas canvas, int counter, float cx, float cy, float alpha) {
        String count = counter > 99 ? "99+" : String.valueOf(counter);
        countPaint.setTextSize(dp(9f));
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
     * Fokusdagi donaning CHAP tomoniga chiqadigan shisha yorliq-qanot:
     * ingichka bog'lovchi chiziq + 24dp kapsula. Nom faqat shu yerda
     * ko'rinadi — qolgan donalar faqat ikonkali, ustun ixcham qoladi.
     */
    private void drawWing(Canvas canvas, String label, float x, float y, float r,
                          float alpha) {
        if (label == null || label.isEmpty()) {
            return;
        }
        float h = dp(24f);
        float right = x - r - dp(8f);
        float textRight = right - dp(8f);
        float maxText = textRight - dp(4f) - dp(8f);
        if (maxText < dp(24f)) {
            return;
        }
        textPaint.setTextSize(labelBaseSize);
        if (textPaint.measureText(label) > maxText) {
            label = fitTail(label, maxText);
        }
        float tw = textPaint.measureText(label);
        float left = Math.max(dp(4f), textRight - tw - dp(8f));
        float rr = h / 2f;

        strokePaint.setColor(WING_LINK);
        strokePaint.setStrokeWidth(dp(1.2f));
        canvas.drawLine(right, y, x - r - dp(1.5f), y, strokePaint);

        rect.set(left, y - h / 2f, right, y + h / 2f);
        fillPaint.setColor(WING_FILL);
        fillPaint.setAlpha((int) (0x33 * alpha));
        canvas.drawRoundRect(rect, rr, rr, fillPaint);
        fillPaint.setAlpha(255);
        strokePaint.setColor(WING_EDGE);
        strokePaint.setAlpha((int) (0x4D * alpha));
        canvas.drawRoundRect(rect, rr, rr, strokePaint);
        strokePaint.setAlpha(255);

        textPaint.setColor(WING_TEXT);
        textPaint.setAlpha((int) (255 * alpha));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(label, textRight, y - (fm.ascent + fm.descent) / 2f, textPaint);
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

    private void drawPencil(Canvas canvas, float cx, float cy, float size) {
        float s = size / 2f;
        canvas.drawLine(cx - s * 0.55f, cy + s * 0.55f, cx + s * 0.5f, cy - s * 0.5f, iconPaint);
        path.reset();
        path.moveTo(cx - s * 0.78f, cy + s * 0.78f);
        path.lineTo(cx - s * 0.55f, cy + s * 0.28f);
        path.lineTo(cx - s * 0.05f, cy + s * 0.78f);
        path.close();
        canvas.drawPath(path, iconPaint);
        canvas.drawLine(cx + s * 0.28f, cy - s * 0.78f, cx + s * 0.78f, cy - s * 0.28f, iconPaint);
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

    private void animateTo(float target, boolean fling) {
        if (animator != null) {
            animator.cancel();
        }
        float from = pos;
        if (Math.abs(target - from) < 0.001f) {
            pos = target;
            invalidate();
            return;
        }
        int dur = (int) Math.min(480, 160 + Math.abs(target - from) * 130);
        animator = ValueAnimator.ofFloat(from, target);
        animator.setDuration(dur);
        animator.setInterpolator(fling ? new OvershootInterpolator(1.1f)
                : new OvershootInterpolator(0.8f));
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
            startPulse();
            if (listener != null) {
                listener.onFolderSelected(idx);
            }
        } else {
            invalidate();
        }
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
            float r = (focus ? dp(FOCUS_R_DP) : dp(NODE_R_DP)) * p[2] + dp(9f);
            float dx = x - p[0], dy = y - p[1];
            if (dx * dx + dy * dy <= r * r) {
                return i;
            }
        }
        return -1;
    }

    /** Bir dona tik oralig'i (px) — surish koeffitsienti. */
    private float spacingPx() {
        return dp(SPACING_DP);
    }

    /** Sensor maydoni — faqat kapsula ustuni (yonidagi chat qatorlari ochiq). */
    private boolean insideCapsule(float x, float y) {
        float cx = getWidth() - dp(SPINE_INSET_DP);
        float halfW = dp(CAPSULE_W_DP) / 2f + dp(10f);
        float top = dp(CAPSULE_TOP_DP) - dp(6f);
        float bottom = getHeight() - dp(CAPSULE_TOP_DP) + dp(6f);
        return x >= cx - halfW && x <= cx + halfW && y >= top && y <= bottom;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (slotCount() == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!insideCapsule(event.getX(), event.getY())) {
                    return false;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (animator != null) {
                    animator.cancel();
                    animator = null;
                }
                downY = event.getY();
                lastY = event.getY();
                lastT = event.getEventTime();
                downPos = pos;
                velocity = 0f;
                dragging = false;
                pressItem = slotAt(event.getX(), event.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float y = event.getY();
                float t = event.getEventTime();
                float dt = Math.max(1f, t - lastT);
                float v = (y - lastY) / dt * 1000f;
                velocity = velocity * 0.6f + v * 0.4f;
                lastY = y;
                lastT = t;
                float dy = y - downY;
                if (!dragging && Math.abs(dy) > dp(8f) && slotCount() > 1) {
                    dragging = true;
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
                boolean wasDragging = dragging;
                boolean tap = !dragging && event.getActionMasked() == MotionEvent.ACTION_UP;
                dragging = false;
                if (tap && pressItem >= 0) {
                    int tapped = pressItem;
                    pressItem = -1;
                    int type = itemType(tapped);
                    if (type == ITEM_COMPOSE) {
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
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
