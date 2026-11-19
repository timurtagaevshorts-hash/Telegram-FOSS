package uz.kodlikino.top4.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;

/**
 * YuzGram pastki navigatsiya bari — Telegram uslubidagi yassi panel.
 *
 * Tuzilishi (4 teng slot):  Chatlar | Reels | Funksiyalar | Profil
 *
 * Ko'rinish butunlay Telegram mavzusidan olinadi:
 *  - sirt — `key_windowBackgroundWhite` (Neon'da qora, kunduzgi mavzuda oq);
 *  - tanlangan slot — mavzu aksenti `key_chats_actionBackground`
 *    (Neon'da pushti — shuning uchun birinchi ochilishdagi rang o'zgarmaydi);
 *  - tanlanmagan slot — `key_chats_menuItemIcon`.
 * Mavzu almashganda bar darhol yangi ranglarga o'tadi (didSetNewTheme).
 *
 * Animatsiya minimal: tab almashganda ikon/matn rangi aksentga qarab silliq
 * o'tadi va tanlangan ikon bir marta "pop" qiladi. Glow, glass, pill va
 * gradientlar olib tashlangan — Telegram'da bunday qatlamlar yo'q.
 *
 * Ikonkalar Canvas'da chiziladi (resursga bog'liq emas).
 */
public class YuzGramBottomBar extends View {

    public static final int TAB_ASOSIY = 0;
    public static final int TAB_REELS = 1;
    public static final int TAB_ELONLAR = 2;
    public static final int TAB_PROFIL = 3;
    public static final int COUNT = 4;

    /** Panelning umumiy balandligi (dip). */
    public static final int HEIGHT_DP = 68;

    private static final String[] LABELS = {"Chatlar", "Reels", "Funksiyalar", "Profil"};

    // Mavzudan hal qilinadigan ranglar — applyThemeColors() to'ldiradi.
    private int surfaceColor;
    private int dividerColor;
    private int accentColor;
    private int iconIdleColor;
    private int textIdleColor;
    private int avatarRingIdleColor;

    /** Mavzu almashganda bar darhol yangi Telegram ranglariga o'tadi. */
    private final NotificationCenter.NotificationCenterDelegate themeObserver =
            (id, account, args) -> {
                if (id == NotificationCenter.didSetNewTheme) {
                    applyThemeColors();
                }
            };

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF panelRect = new RectF();
    private final RectF tmpRect = new RectF();
    private final Path path = new Path();

    private int activeTab = TAB_ASOSIY;
    /** Eski API: chat ro'yxatidagi "yangi chat" holati (ikon endi o'zgarmaydi). */
    private boolean homeCompose;
    private float indicatorPos = 0f;   // tab-space 0..COUNT-1, animatsiyalanadi
    private int popTab = -1;           // "pop" animatsiyasi ketayotgan tab
    private float popAmount = 0f;      // 0..1
    private int pressSlot = -1;        // bosilayotgan slot (0..COUNT-1)
    private float pressAmount = 0f;    // 0..1
    private boolean shown = false;

    /** Profil slotidagi dumaloq avatar — Telegram bilan bir xil manba va fallback. */
    private final ImageReceiver avatarReceiver = new ImageReceiver(this);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private long avatarUserId;
    private long avatarPhotoId;

    private OnTabSelectedListener listener;

    // Typeface'ni har kadrda yaratmaymiz (4 slot x 60 fps = tebranish)
    private Typeface regularTypeface;
    private Typeface boldTypeface;

    private final float textSize = AndroidUtilities.dp(10.5f);
    private final float iconSize = AndroidUtilities.dp(20.5f);

    // Geometriya (onDraw hisoblaydi)
    private float panelLeft, panelRight, panelTop, panelBottom;
    private final float[] slotCx = new float[COUNT];

    public interface OnTabSelectedListener {
        /**
         * @return true — tab ochildi va bar faol holatni yangilashi mumkin;
         * false — o'tish amalga oshmadi (masalan animatsiya ketmoqda), faol
         * tab joyida qoladi. Ilgari highlight har doim siljib ketardi va
         * ekran ochilmay qolganda foydalanuvchi "qotib qoldi" deb o'ylardi.
         */
        boolean onTabSelected(int tab);
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
        dividerPaint.setStyle(Paint.Style.FILL);
        indicatorPos = activeTab;
        avatarReceiver.setDelegate((receiver, set, thumb, memCache) -> invalidate());
        applyThemeColors();
    }

    private void applyThemeColors() {
        surfaceColor = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhite);
        accentColor = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_chats_actionBackground);
        iconIdleColor = org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_chats_menuItemIcon);
        textIdleColor = iconIdleColor;
        avatarRingIdleColor = (iconIdleColor & 0x00FFFFFF) | 0x40000000;
        // Nozik ajratuvchi chiziq — mavzu aksentidan 20% shaffoflik bilan.
        dividerColor = (accentColor & 0x00FFFFFF) | 0x33000000;
        invalidate();
    }

    /**
     * Profil slotidagi avatarni joriy akkauntga moslaydi. Telegram'ning
     * o'zidagi kabi: surat bo'lsa ImageReceiver yuklaydi, bo'lmasa
     * AvatarDrawable bosh harflar + rangli doirani chizadi.
     * Har chaqiruvda ishlaydi, lekin user/photo o'zgarmasa — hech narsa qilmaydi.
     */
    public void updateAvatar() {
        int account = UserConfig.selectedAccount;
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            return;
        }
        if (avatarReceiver.getCurrentAccount() != account) {
            // Akkaunt almashdi — ImageReceiver keshi boshqa akkauntniki.
            avatarReceiver.setCurrentAccount(account);
            avatarUserId = 0;
            avatarPhotoId = 0;
        }
        long photoId = user.photo != null ? user.photo.photo_id : 0;
        if (user.id == avatarUserId && photoId == avatarPhotoId) {
            return;
        }
        avatarUserId = user.id;
        avatarPhotoId = photoId;
        avatarDrawable.setInfo(user);
        avatarReceiver.setForUserOrChat(user, avatarDrawable);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        avatarReceiver.onAttachedToWindow();
        NotificationCenter.getGlobalInstance()
                .addObserver(themeObserver, NotificationCenter.didSetNewTheme);
        updateAvatar();
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance()
                .removeObserver(themeObserver, NotificationCenter.didSetNewTheme);
        avatarReceiver.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }

    public static int height() {
        return AndroidUtilities.dp(HEIGHT_DP);
    }

    /**
     * Balandlikni qat'iy dp(HEIGHT_DP)ga qotamiz. LaunchActivity'dagi frameLayout
     * (maxsus FrameLayout) bolaga ba'zan kutilganidan katta balandlik bermoqda —
     * bunda bar butun ekranni egallab qolardi. EXACTLY spec buni oldini oladi.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height(), MeasureSpec.EXACTLY));
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    /**
     * Chatlar slotidagi qayta bosish menyusi uchun holat bayrog'i (LaunchActivity
     * o'qiydi). Bar ko'rinishi bunga bog'liq emas — ikon Telegram'dagi kabi doimiy.
     */
    public void setHomeCompose(boolean compose) {
        if (homeCompose != compose) {
            homeCompose = compose;
            invalidate();
        }
    }

    public boolean isHomeCompose() {
        return homeCompose;
    }

    /** Slot markazining View ichidagi x koordinatasi — menyuni shu yerga bog'laymiz. */
    public float slotCenterX(int tab) {
        if (tab < 0 || tab >= COUNT) {
            return getWidth() / 2f;
        }
        if (panelRight > panelLeft) {
            return slotCx[tab];
        }
        return getWidth() * (tab + 0.5f) / COUNT;
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
     * Slotlar orasidagi o'tish: masofaga qarab davomiylik va OvershootInterpolator —
     * rang/ikon holati silliq ko'chadi.
     */
    private void animateIndicatorTo(int target, boolean animated) {
        if (indicatorAnim != null) {
            indicatorAnim.cancel();
        }
        travelFrom = indicatorPos;
        travelTo = target;
        if (!animated || Math.abs(travelTo - travelFrom) < 0.01f) {
            indicatorPos = target;
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

        panelLeft = 0;
        panelRight = w;
        panelTop = 0;
        panelBottom = h;
        panelRect.set(panelLeft, panelTop, panelRight, panelBottom);

        float usable = panelRight - panelLeft;
        for (int i = 0; i < COUNT; i++) {
            slotCx[i] = panelLeft + usable * (i + 0.5f) / COUNT;
        }

        float iconCy = AndroidUtilities.dp(23f);
        float textBaseline = panelBottom - AndroidUtilities.dp(19f);
        float ind = clampIndicator();

        // --- Telegram uslubidagi yassi sirt + nozik yuqori ajratuvchi ---
        surfacePaint.setColor(surfaceColor);
        canvas.drawRect(panelRect, surfacePaint);
        dividerPaint.setColor(dividerColor);
        canvas.drawRect(panelLeft, panelTop, panelRight, panelTop + AndroidUtilities.dp(1f), dividerPaint);

        // --- Slotlar: ikon + matn (aksentga qarab crossfade) ---
        for (int i = 0; i < COUNT; i++) {
            float prox = 1f - Math.min(1f, Math.abs(i - ind));
            float squeeze = i == pressSlot ? 1f - 0.07f * pressAmount : 1f;
            float cx = slotCx[i];

            boolean bold = prox > 0.5f;
            int iconColor = lerpColor(iconIdleColor, accentColor, easeOut(prox));
            int textColor = lerpColor(textIdleColor, accentColor, easeOut(prox));

            float scale = (1f + 0.06f * prox) * squeeze;
            if (popTab == i && popAmount > 0.01f) {
                scale *= 1f + 0.14f * popAmount;
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
                // Chatlar: suhbat ko'pigi + chap-pastdagi quloqcha.
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
                // Telegram'dagi kabi dumaloq avatar: surat bo'lsa surat, bo'lmasa
                // bosh harflar + rangli doira (AvatarDrawable). Halqa faol tabda
                // yorqinlashadi — slot tanlanganini ikonkasiz ham bildiradi.
                float r = AndroidUtilities.dp(10.6f) * k;
                avatarReceiver.setImageCoords(cx - r, cy - r, r * 2f, r * 2f);
                avatarReceiver.setRoundRadius((int) r);
                avatarReceiver.draw(canvas);
                iconPaint.setStyle(Paint.Style.STROKE);
                iconPaint.setStrokeWidth(AndroidUtilities.dp(active ? 1.7f : 1.3f) * k);
                iconPaint.setColor(active ? accentColor : avatarRingIdleColor);
                canvas.drawCircle(cx, cy, r - iconPaint.getStrokeWidth() / 2f, iconPaint);
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
            // #185: ro'yxat bar ortidan o'tadi — dock panelidan tashqaridagi
            // (shaffof chekka/bo'shliq) teginish ro'yxatga o'tsin, aks holda
            // bar 68dp tasmada skroll va qator bosishini yutib yuboradi.
            if (!isInsidePanel(event.getX(), event.getY())) {
                pressSlot = -1;
                return false;
            }
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
                boolean handled = listener == null || listener.onTabSelected(slot);
                if (handled) {
                    if (slot != activeTab) {
                        setActiveTab(slot);
                    } else {
                        // qayta bosish: indicator joyida, faqat ikon "pop" qiladi
                        animatePop(slot, true);
                    }
                } else {
                    // O'tish amalga oshmadi — highlight avvalgi tab'da qoladi.
                    animatePop(activeTab, true);
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

    /** #185: faqat ko'rinadigan dock paneli teginishni ushlaydi. */
    private boolean isInsidePanel(float x, float y) {
        return panelRight > panelLeft && x >= panelLeft && x <= panelRight
                && y >= panelTop && y <= panelBottom;
    }
}
