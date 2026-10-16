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
    private static final float ITEM_H_DP = 58f;
    private static final float CIRCLE_DP = 34f;
    private static final float LABEL_SP = 8.6f;
    private static final float V_PAD_DP = 10f;

    // Neon palitra (YuzGram brendi — pastki bar bilan bir xil)
    private static final int SURFACE = 0xF20A1120;
    private static final int BORDER = 0x592AABEE;
    private static final int GLOW = 0x662AABEE;
    private static final int ACTIVE_START = 0xFF38B6FF;
    private static final int ACTIVE_END = 0xFF5B6CF9;
    private static final int INACTIVE_FILL = 0x40243A5E;
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

    private ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
    private CounterProvider counterProvider;
    private boolean addTile;
    private Runnable addFolderAction;
    private int selectedIndex = 0;
    private Listener listener;

    private float scrollY = 0f;
    private float contentHeight = 0f;
    private float downY = 0f;
    private float downScroll = 0f;
    private boolean dragging = false;
    private float pressIndex = -1f;
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
        iconPaint.setStrokeWidth(dp(1.7f));
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
        return dp(V_PAD_DP) * 2f + itemCount() * itemHeight();
    }

    private float maxScroll() {
        return Math.max(0f, totalContentHeight() - getHeight());
    }

    private void ensureVisible(int index) {
        if (getHeight() == 0) {
            return;
        }
        float top = dp(V_PAD_DP) + index * itemHeight();
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
            h = (int) Math.ceil(Math.max(contentHeight, itemHeight() + dp(V_PAD_DP) * 2f));
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
        if (filters.isEmpty() || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        float inset = dp(4);
        rect.set(inset, inset, w - inset, h - inset);
        float radius = (rect.width()) / 2f;
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        float cxBase = w / 2f;
        int n = itemCount();
        float amp = dp(3.2f);
        // Elementlar (va badgilar) doim panel ichida qolishi uchun iqlimlaymiz.
        int clipSave = canvas.save();
        canvas.clipRect(inset, inset, w - inset, h - inset);
        for (int i = 0; i < n; i++) {
            float top = dp(V_PAD_DP) + i * itemHeight() - scrollY;
            if (top + itemHeight() < inset || top > h - inset + itemHeight()) {
                continue;
            }
            float cy = top + itemHeight() / 2f;
            // sinus bo'rttigi: o'rtadagi elementlar biroz ichkariga (chapga)
            float t = n > 1 ? (float) i / (n - 1) : 0.5f;
            float s = (float) Math.sin(Math.PI * t);
            float cx = cxBase - amp * s;
            drawItem(canvas, i, cx, cy, dp(CIRCLE_DP), i == selectedIndex);
        }
        canvas.restoreToCount(clipSave);
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
            fillPaint.setShader(activeShader);
            iconPaint.setColor(ICON_ACTIVE);
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r, fillPaint);
            fillPaint.setShader(null);
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
        float maxTextW = getWidth() - dp(9);
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
        float top = dp(V_PAD_DP) - scrollY;
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
        if (filters.isEmpty()) {
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
                int i = indexAt(downY);
                if (i >= 0) {
                    pressIndex = i;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - downY;
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
                pressIndex = -1;
                if (!dragging && event.getActionMasked() == MotionEvent.ACTION_UP) {
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
