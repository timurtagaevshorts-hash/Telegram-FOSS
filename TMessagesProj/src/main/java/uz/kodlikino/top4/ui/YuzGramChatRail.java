package uz.kodlikino.top4.ui;

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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.ArrayList;

/**
 * YuzGram chat papkalari uchun O'NG chetdagi vertikal ustun (rail).
 *
 * Eski KinoTOP4 loyihasidagidek: ekranning o'ng burchagida, qora neon fonli,
 * vertikal chiziqli kategoriya paneli. Gorizontal FilterTabsView o'rnini bosadi.
 *
 * Xususiyatlari:
 *  - Manba: Telegramning o'z chat papkalari (MessagesController.getDialogFilters()).
 *  - Har bir element: yumaloq ikonka + ostida qisqa nom + o'qilmaganlar badgisi.
 *  - Faol element: gradient (ko'k->binafsha) doira + nur; nomlar oq.
 *  - Elementlar "sinus bo'rttigi" bilan joylashadi (KinoTOP4'dagidek).
 *  - Papkalar ko'p bo'lsa — ustun ichida vertikal aylantirish mumkin.
 *
 * Ikonkalar Canvas'da chiziladi (resurs/folderga bog'liq emas), shuning uchun
 * mavzu (light/dark) qanday bo'lishidan qat'i-nazar doim qora-neon ko'rinishda.
 */
public class YuzGramChatRail extends View {

    /** Ustun kengligi (panel + o'ng chekkaye). */
    public static final int WIDTH_DP = 64;
    private static final float ITEM_H_DP = 53f;
    private static final float CIRCLE_DP = 31f;
    private static final float LABEL_SP = 9.2f;
    private static final float V_PAD_DP = 10f;
    /** Pastki yumaloq chet oxirgi label'ni kesmasligi uchun qo'shimcha joy. */
    private static final float V_PAD_BOTTOM_DP = 26f;

    // Neon palitra (YuzGram brendi — pastki bar bilan bir xil)
    private static final int SURFACE = 0xF20A1120;
    private static final int BORDER = 0x592AABEE;
    private static final int GLOW = 0x662AABEE;
    private static final int ACTIVE_START = 0xFF38B6FF;
    private static final int ACTIVE_END = 0xFF5B6CF9;
    private static final int INACTIVE_FILL = 0x4D2A4570;
    private static final int INACTIVE_STROKE = 0x7338B6FF;
    private static final int ICON_ACTIVE = 0xFFFFFFFF;
    private static final int ICON_IDLE = 0xFFBFD0EA;
    private static final int TEXT_IDLE = 0xFF8E9DBB;
    private static final int BADGE = 0xFFFF4D6D;

    /** Ikonka turi (flags'dan aniqlanadi). */
    private static final int GLYPH_CHAT = 0;
    private static final int GLYPH_USER = 1;
    private static final int GLYPH_GROUP = 2;
    private static final int GLYPH_CHANNEL = 3;
    private static final int GLYPH_BOT = 4;
    private static final int GLYPH_ARCHIVE = 5;
    private static final int GLYPH_LIST = 6;
    private static final int GLYPH_PENCIL = 7;
    private static final int GLYPH_CAMERA = 8;

    /** Yuqoridagi amaliyot plitalari (pastki FAB'lardan ko'chirilgan). */
    public static final int ACTION_COMPOSE = 0;
    public static final int ACTION_CAMERA = 1;

    public interface ActionListener {
        void onActionClicked(int action);
    }

    public interface Listener {
        /** @param index getDialogFilters() bo'yicha indeks */
        void onFolderSelected(int index);
    }

    /**
     * O'qilmaganlar sonini chizish paytida so'raymiz — shunda badgilar
     * doimiy yangi qoladi (snapshot saqlashning o'rniga).
     */
    public interface CounterProvider {
        int getCounter(MessagesController.DialogFilter filter);
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // TextPaint: TextUtils.ellipsize(...) aynan TextPaint talab qiladi.
    private final android.text.TextPaint textPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    // Yarim-doira (o'ng tomoni tekis) shakli uchun burchak radiuslari (qayta alloc qilmaslik uchun).
    private final float[] cornerRadii = new float[8];

    private ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
    private CounterProvider counterProvider;
    private boolean addTile;
    private Runnable addFolderAction;
    private int selectedIndex = 0;
    private Listener listener;
    private ActionListener actionListener;
    private boolean showCompose, showCamera;
    private int pressAction = -1;

    private float scrollY = 0f;
    private float contentHeight = 0f;
    private float downY = 0f;
    private float downScroll = 0f;
    private boolean dragging = false;
    private float pressIndex = -1f;
    private int maxRailHeight;
    private LinearGradient activeShader;
    private float activeShaderCx = Float.MIN_VALUE;
    private float activeShaderCy = Float.MIN_VALUE;
    private float activeShaderR = Float.MIN_VALUE;

    public YuzGramChatRail(Context context) {
        super(context);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(false);
        setWillNotDraw(false);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(SURFACE);
        bgPaint.setShadowLayer(dp(10), 0, 0, GLOW);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(BORDER);
        fillPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(2f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP,
                getResources().getDisplayMetrics()));
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTextSize(dp(8f));
        badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(BADGE);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // setShadowLayer (nur) uchun
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Papkalar yo'q holda ustunda "+" tugmasi ko'rsatiladi va shu ishga yuradi. */
    public void setOnAddFolder(Runnable r) {
        addFolderAction = r;
    }

    public void setCounterProvider(CounterProvider p) {
        counterProvider = p;
    }

    public void setActionListener(ActionListener l) {
        actionListener = l;
    }

    /** Yuqoridagi amaliyot plitalarini (pencil/kamera) ko'rsatishni boshqaradi. */
    public void setActions(boolean compose, boolean camera) {
        if (showCompose == compose && showCamera == camera) {
            return;
        }
        showCompose = compose;
        showCamera = camera;
        requestLayout();
        invalidate();
    }

    private int actionCount() {
        return (showCompose ? 1 : 0) + (showCamera ? 1 : 0);
    }

    /** Papkalardan yuqoridagi qat'iy (aylanmaydigan) amaliyot sarlavhasi + tepa bo'shliq. */
    private float topInset() {
        return dp(V_PAD_DP) + actionCount() * itemHeight();
    }

    /** Ko'rinadigan slot (0..) ni haqiqiy amaliyot turiga aylantiradi. */
    private int actionTypeAt(int slot) {
        int idx = 0;
        if (showCompose) {
            if (slot == idx) return ACTION_COMPOSE;
            idx++;
        }
        if (showCamera) {
            if (slot == idx) return ACTION_CAMERA;
            idx++;
        }
        return -1;
    }

    /** y sarvisi sarlavhadagi amaliyot plitasiga tegishli bo'lsa slot qaytaradi, aksincha -1. */
    private int actionSlotAt(float y) {
        int ac = actionCount();
        if (ac == 0) {
            return -1;
        }
        float top = dp(V_PAD_DP);
        if (y < top) {
            return -1;
        }
        int a = (int) ((y - top) / itemHeight());
        if (a < 0 || a >= ac) {
            return -1;
        }
        float local = y - (top + a * itemHeight());
        if (local > itemHeight() - dp(2f)) {
            return -1;
        }
        return a;
    }

    /**
     * Papkalarni qayta o'rnatadi.
     *
     * @param newFilters Telegram papkalari ro'yxati (0-indeks = "Barchasi")
     * @param selected   faol indeks
     * @param showAdd    papkalar kam bo'lsa ""+" tugmasini ko'rsatish
     * @param animated   o'zgarish animatsiyasi
     */
    public void setFilters(ArrayList<MessagesController.DialogFilter> newFilters,
                           int selected, boolean showAdd, boolean animated) {
        if (newFilters == null) {
            newFilters = new ArrayList<>();
        }
        boolean same = newFilters.size() == filters.size() && selected == selectedIndex
                && (showAdd && addFolderAction != null) == addTile;
        if (same) {
            for (int i = 0; i < newFilters.size(); i++) {
                if (newFilters.get(i) != filters.get(i)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) {
            return;
        }
        filters = newFilters;
        addTile = showAdd && addFolderAction != null;
        selectedIndex = Math.max(0, Math.min(selected, Math.max(0, filters.size() - 1)));
        if (!animated) {
            scrollY = 0;
        }
        ensureVisible(selectedIndex);
        requestLayout();
        invalidate();
    }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= filters.size() || index == selectedIndex) {
            return;
        }
        selectedIndex = index;
        ensureVisible(index);
        invalidate();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public static int width() {
        return AndroidUtilities.dp(WIDTH_DP);
    }

    /**
     * Ustunning maksimal balandligi (px). Pastki bar va qalam FAB'i bilan
     * to'qnashganda ustun ichida aylantiriladi.
     */
    public void setMaxRailHeight(int px) {
        if (maxRailHeight != px) {
            maxRailHeight = px;
            requestLayout();
        }
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private float itemHeight() {
        return dp(ITEM_H_DP);
    }

    /** Elementlar soni: papkalar + kerak bo'lsa "papka qo'shish" tugmasi. */
    private int itemCount() {
        return filters.size() + (addTile ? 1 : 0);
    }

    private float totalContentHeight() {
        return topInset() + dp(V_PAD_BOTTOM_DP) + itemCount() * itemHeight();
    }

    private float maxScroll() {
        return Math.max(0f, totalContentHeight() - getHeight());
    }

    private void ensureVisible(int index) {
        if (getHeight() == 0) {
            return;
        }
        float top = topInset() + index * itemHeight();
        float bottom = top + itemHeight();
        float m = maxScroll();
        if (top - scrollY < 0) {
            scrollY = Math.max(0, top - itemHeight());
        } else if (bottom - scrollY > getHeight()) {
            scrollY = Math.min(m, bottom - getHeight() + itemHeight() * 0.15f);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = width();
        contentHeight = totalContentHeight();
        int specH = MeasureSpec.getSize(heightMeasureSpec);
        int h;
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            h = specH;
        } else if (specH > 0 && contentHeight > specH) {
            // Papkalar ko'p — ekranga sig'maydi: o'lcham cheklanadi, ichida aylantiriladi.
            h = specH;
        } else {
            h = (int) Math.ceil(Math.max(contentHeight, itemHeight() + dp(V_PAD_DP) + dp(V_PAD_BOTTOM_DP)));
        }
        if (maxRailHeight > 0 && h > maxRailHeight) {
            h = maxRailHeight;
        }
        // Balandlikni qat'iy qotamiz: maxsus FrameLayout bolaga kutilganidan katta
        // balandlik bersa, panel butun ekranni egallib qo'yardi (YuzGramBottomBar'dagi
        // xuddi shu xato).
        setMeasuredDimension(w, h);
        scrollY = Math.min(scrollY, maxScroll());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scrollY = Math.min(scrollY, maxScroll());
        ensureVisible(selectedIndex);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if ((filters.isEmpty() && actionCount() == 0) || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        float inset = dp(3);
        // Shakl: o'ng tomoni TEKIS (ekran chekkasiga yopishgan), chap tomoni
        // yarim-doira kabi botiq (yuqori va pastki chap burchaklar = w/2 radius).
        rect.set(inset, inset, w - dp(1f), h - inset);
        float radius = rect.width() / 2f;
        // radii tartibi: top-left, top-right, bottom-right, bottom-left (har biri x,y)
        cornerRadii[0] = radius; cornerRadii[1] = radius; // top-left  (dumaloq)
        cornerRadii[2] = 0f;      cornerRadii[3] = 0f;     // top-right (tekis)
        cornerRadii[4] = 0f;      cornerRadii[5] = 0f;     // bottom-right (tekis)
        cornerRadii[6] = radius;  cornerRadii[7] = radius; // bottom-left (dumaloq)
        path.reset();
        path.addRoundRect(rect, cornerRadii, Path.Direction.CW);
        canvas.drawPath(path, bgPaint);
        canvas.drawPath(path, strokePaint);

        int clipSave = canvas.save();
        canvas.clipPath(path);

        float cxBase = w / 2f;
        float amp = dp(3.2f);

        // 1) Yuqoridagi amaliyot plitalari (qalamcha + kamera) — qat'iy, aylanmaydi.
        int ac = actionCount();
        for (int a = 0; a < ac; a++) {
            float cy = dp(V_PAD_DP) + a * itemHeight() + itemHeight() / 2f;
            drawAction(canvas, actionTypeAt(a), cxBase, cy, dp(CIRCLE_DP));
        }

        // 2) Papkalar — amaliyot sarlavhasidan pastda, ichida aylantiriladi.
        //    Aylantirilganda sarlavhadagi plitalar ustiga chiqmasligi uchun
        //    faqat topInset() dan pastga qirqamiz.
        int n = itemCount();
        int folderClip = canvas.save();
        canvas.clipRect(inset, topInset(), w - inset, h - inset);
        float folderTop0 = topInset() - scrollY;
        for (int i = 0; i < n; i++) {
            float top = folderTop0 + i * itemHeight();
            if (top + itemHeight() < topInset() || top > h - inset + itemHeight()) {
                continue;
            }
            float cy = top + itemHeight() / 2f;
            // sinus bo'rttigi: o'rtadagi elementlar biroz ichkariga (chapga)
            float t = n > 1 ? (float) i / (n - 1) : 0.5f;
            float s = (float) Math.sin(Math.PI * t);
            float cx = cxBase - amp * s;
            drawItem(canvas, i, cx, cy, dp(CIRCLE_DP), i == selectedIndex);
        }
        canvas.restoreToCount(folderClip);
        canvas.restoreToCount(clipSave);
    }

    /**
     * Yuqoridagi amaliyot plitasi: papkalardek doira ichida pencil/kamera ikonkasi.
     * Bosilganda actionListener orqali DialogsActivity ga xabar beriladi.
     */
    private void drawAction(Canvas canvas, int action, float cx, float cy, float d) {
        float r = d / 2f;
        float scale = pressAction == action ? 0.9f : 1f;
        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        fillPaint.setColor(INACTIVE_FILL);
        canvas.drawCircle(cx, cy, r, fillPaint);
        strokePaint.setColor(INACTIVE_STROKE);
        canvas.drawCircle(cx, cy, r, strokePaint);
        strokePaint.setColor(BORDER);
        iconPaint.setColor(ICON_IDLE);
        float glyph = d * 0.56f;
        float gcy = cy;
        if (action == ACTION_CAMERA) {
            drawCamera(canvas, cx, gcy, glyph);
        } else {
            drawPencil(canvas, cx, gcy, glyph);
        }
        canvas.restore();
    }

    private void drawItem(Canvas canvas, int index, float cx, float cy, float d, boolean active) {
        boolean add = index >= filters.size();
        MessagesController.DialogFilter filter = add ? null : filters.get(index);
        float r = d / 2f;
        float scale = 1f;
        if (pressIndex == index) {
            scale = 0.92f;
        }
        canvas.save();
        canvas.scale(scale, scale, cx, cy);

        if (active) {
            // Gradient shader'ni har kadrda yaratmaymiz — faqat geometriya
            // o'zgarganda qayta hisoblanadi (onDraw'da allocation qilmaslik uchun).
            if (activeShader == null || activeShaderCx != cx || activeShaderCy != cy || activeShaderR != r) {
                activeShader = new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                        new int[]{ACTIVE_START, ACTIVE_END}, null, Shader.TileMode.CLAMP);
                activeShaderCx = cx;
                activeShaderCy = cy;
                activeShaderR = r;
            }
            // Muhim: fillPaint'da oldingi kadrdan qolgan shaffoflik (0x40)
            // shader'ga ham ta'sir qiladi — gradient xira (to'q ko'k) bo'lib
            // qolmasligi uchun avval to'liq opaq rang qo'yamiz.
            fillPaint.setColor(0xFFFFFFFF);
            fillPaint.setShader(activeShader);
            iconPaint.setColor(ICON_ACTIVE);
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r, fillPaint);
            fillPaint.setShader(null);
            // Faol papka atrofidagi neon halqa — tanlov vizual og'irligi.
            strokePaint.setColor(GLOW);
            strokePaint.setStrokeWidth(dp(2f));
            canvas.drawCircle(cx, cy, r + dp(2.5f), strokePaint);
            strokePaint.setStrokeWidth(dp(1f));
            strokePaint.setColor(BORDER);
        } else {
            fillPaint.setColor(INACTIVE_FILL);
            canvas.drawCircle(cx, cy, r, fillPaint);
            strokePaint.setColor(INACTIVE_STROKE);
            canvas.drawCircle(cx, cy, r, strokePaint);
            strokePaint.setColor(BORDER);
            iconPaint.setColor(ICON_IDLE);
            textPaint.setColor(TEXT_IDLE);
        }

        float glyph = d * 0.52f;
        if (add) {
            drawPlus(canvas, cx, cy - dp(3.5f), glyph);
        } else {
            drawGlyph(canvas, glyphType(filter, index), cx, cy - dp(3.5f), glyph);
        }

        // Nom (ikonka ostida)
        String label = add ? LocaleController.getString("Add", R.string.Add) : labelFor(filter);
        textPaint.setAlpha(active ? 255 : 215);
        float textY = cy + r + dp(7.5f);
        float maxTextW = labelMaxWidth(textY);
        CharSequence ell = android.text.TextUtils.ellipsize(label, textPaint,
                (int) Math.max(dp(24), maxTextW), android.text.TextUtils.TruncateAt.END);
        canvas.drawText(ell.toString(), cx, textY, textPaint);
        textPaint.setAlpha(255);

        // Badgi
        int counter = 0;
        if (!add && counterProvider != null) {
            counter = Math.max(0, counterProvider.getCounter(filter));
        }
        if (counter > 0) {
            String txt = counter > 99 ? "99+" : String.valueOf(counter);
            float bw = Math.max(dp(14f), badgeTextPaint.measureText(txt) + dp(6f));
            float bx = cx + r - bw / 2f + dp(2f);
            float by = cy - r + dp(1f);
            rect.set(bx - bw / 2f, by - dp(7f), bx + bw / 2f, by + dp(7f));
            canvas.drawRoundRect(rect, dp(7f), dp(7f), badgePaint);
            badgeTextPaint.setColor(0xFFFFFFFF);
            canvas.drawText(txt, bx, by + dp(3f), badgeTextPaint);
        }
        if (filter != null && filter.locked) {
            // qulupnay belgisi — kichik nuqta
            fillPaint.setColor(0xFFFFC24B);
            canvas.drawCircle(cx - r + dp(3f), cy - r + dp(3f), dp(2.6f), fillPaint);
        }
        canvas.restore();
    }

    /**
     * Panelning yumaloq uchlari label'ni kesib qo'ymasligi uchun yozuv
     * chiziladigan qatordagi haqiqiy ochiq kenglikni hisoblaymiz.
     */
    private float labelMaxWidth(float textY) {
        float inset = dp(4);
        float w = getWidth();
        float radius = (w - inset * 2f) / 2f;
        float d = (getHeight() - inset) - (textY + dp(3.5f));
        float squeeze = 0f;
        if (d < radius) {
            float k = radius - Math.max(0f, d);
            squeeze = radius - (float) Math.sqrt(Math.max(0f, radius * radius - k * k));
        }
        return Math.max(dp(22), w - inset * 2f - squeeze * 2f - dp(3));
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

    /** Papka bayroqlaridan ikonka turini tanlaydi. */
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

    private void drawPlus(Canvas canvas, float cx, float cy, float size) {
        float s = size / 2f;
        canvas.drawLine(cx - s * 0.85f, cy, cx + s * 0.85f, cy, iconPaint);
        canvas.drawLine(cx, cy - s * 0.85f, cx, cy + s * 0.85f, iconPaint);
    }

    /** Qalamcha (yangi xabar) ikonkasi — diagonal qalam. */
    private void drawPencil(Canvas canvas, float cx, float cy, float size) {
        float s = size / 2f;
        // korpus (diagonal)
        canvas.drawLine(cx - s * 0.55f, cy + s * 0.55f, cx + s * 0.5f, cy - s * 0.5f, iconPaint);
        // uch qismi (kichik uchburchak)
        path.reset();
        path.moveTo(cx - s * 0.78f, cy + s * 0.78f);
        path.lineTo(cx - s * 0.55f, cy + s * 0.28f);
        path.lineTo(cx - s * 0.05f, cy + s * 0.78f);
        path.close();
        canvas.drawPath(path, iconPaint);
        // orqasi (ochirg'ich chiziq)
        canvas.drawLine(cx + s * 0.28f, cy - s * 0.78f, cx + s * 0.78f, cy - s * 0.28f, iconPaint);
    }

    /** Kamera (story) ikonkasi — korp + linza + tepa chiqindisi. */
    private void drawCamera(Canvas canvas, float cx, float cy, float size) {
        float s = size / 2f;
        // tepa chiqindisi (vizir)
        path.reset();
        path.moveTo(cx - s * 0.35f, cy - s * 0.5f);
        path.lineTo(cx - s * 0.18f, cy - s * 0.78f);
        path.lineTo(cx + s * 0.22f, cy - s * 0.78f);
        path.lineTo(cx + s * 0.38f, cy - s * 0.5f);
        canvas.drawPath(path, iconPaint);
        // korpus
        rect.set(cx - s * 0.85f, cy - s * 0.5f, cx + s * 0.85f, cy + s * 0.72f);
        canvas.drawRoundRect(rect, s * 0.24f, s * 0.24f, iconPaint);
        // linza
        canvas.drawCircle(cx, cy + s * 0.12f, s * 0.34f, iconPaint);
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
                // megafon: uchburchak + to'lqin chiziqlari
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
                    float y = cy - s * 0.55f + i * s * 0.55f;
                    canvas.drawLine(cx - s * 0.8f, y, cx + s * 0.8f, y, iconPaint);
                }
                break;
            }
            default: {
                // suhbat shakli: yumaloq to'rtburchak + quyruq
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

    private int indexAt(float y) {
        float top = topInset() - scrollY;
        if (y < top) {
            return -1;
        }
        int i = (int) ((y - top) / itemHeight());
        if (i < 0 || i >= itemCount()) {
            return -1;
        }
        float local = y - (top + i * itemHeight());
        if (local > itemHeight() - dp(2f)) {
            return -1;
        }
        return i;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (filters.isEmpty() && actionCount() == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                // Ustun ustidagi harakat chat listesining papka-svaipiga ketmasin.
                cancelParentAnimations();
                downY = event.getY();
                downScroll = scrollY;
                dragging = false;
                int slot = actionSlotAt(downY);
                if (slot >= 0) {
                    pressAction = slot;
                    pressIndex = -1;
                    invalidate();
                    return true;
                }
                int i = indexAt(downY);
                if (i >= 0) {
                    pressIndex = i;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - downY;
                if (pressAction >= 0) {
                    // Sarlavhadagi plitadan boshlangan bosim aylantirmaydi.
                    if (Math.abs(dy) > dp(10f)) {
                        pressAction = -1;
                        invalidate();
                    }
                    return true;
                }
                if (!dragging && Math.abs(dy) > dp(6f) && maxScroll() > 0) {
                    dragging = true;
                    pressIndex = -1;
                }
                if (dragging) {
                    scrollY = Math.max(0f, Math.min(maxScroll(), downScroll - dy));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasAction = pressAction >= 0;
                int actionSlot = pressAction;
                pressAction = -1;
                pressIndex = -1;
                if (!dragging && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (wasAction) {
                        int type = actionTypeAt(actionSlot);
                        if (actionListener != null && type >= 0) {
                            actionListener.onActionClicked(type);
                        }
                    } else {
                        int i = indexAt(event.getY());
                        if (i >= 0 && i == filters.size() && addTile && addFolderAction != null) {
                            addFolderAction.run();
                        } else if (i >= 0 && i < filters.size()) {
                            MessagesController.DialogFilter f = filters.get(i);
                            if (f != null && f.locked) {
                                shake();
                            } else {
                                selectedIndex = i;
                                ensureVisible(i);
                                if (listener != null) {
                                    listener.onFolderSelected(i);
                                }
                            }
                        }
                    }
                }
                dragging = false;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /**
     * Harakatni ustun olganida ota-ona animatsiyalarini to'xtatamiz: 3 davrdan
     * yuqoriga chiqmaymiz (fragment/katta darajadagi animatsiyalarga tegmaymiz).
     */
    private void cancelParentAnimations() {
        View p = getParent() instanceof View ? (View) getParent() : null;
        for (int i = 0; p != null && i < 3; i++) {
            p.animate().cancel();
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
    }

    private void shake() {
        animate().cancel();
        setTranslationX(0);
        animate().translationX(dp(4)).setDuration(50)
                .withEndAction(() -> animate().translationX(-dp(4)).setDuration(60)
                        .withEndAction(() -> animate().translationX(0).setDuration(60).start())
                        .start())
                .start();
    }
}
