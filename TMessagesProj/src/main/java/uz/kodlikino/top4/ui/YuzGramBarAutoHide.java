package uz.kodlikino.top4.ui;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * YuzGram pastki navigatsiya bari uchun skroll-yo'nalish nazoratchisi
 * (Material "scroll-off" xulosasi, lekin pozitsiyaga emas, HARAKATGA asoslangan).
 *
 * Qoida:
 *  - ro'yxat PASTGA surgilsa (kontent yuqoriga ketadi) bar g'oyib bo'ladi;
 *  - ro'yxat Tepaga surgilsa bar DARHOL qaytadi — sahifa yuqorisiga
 *    chiqib bo'lishi shart emas;
 *  - ro'yxat tepasida bar doim ko'rinadi (overscroll'da ham tiklanadi).
 *
 * Eski DialogsActivity mantig'i "skroll offseti manfiymi" degan savolga
 * qaragan edi — natijada bar yashirilib, foydalanuvchi tepaga qaytmaguncha
 * ko'rinmas edi. Bu klass yo'nalish + bo'sag'a (threshold) hisoblaydi va
 * butun ilova bo'ylab bir xil ishlaydi: Asosiy (chatlar), Reels, Funksiyalar,
 * Profil.
 *
 * Holat LaunchActivity'da yashaydi (bitta haqiqat manbasi) — bu yerda
 * yashirinish flag'i saqlanmaydi, shuning uchun tab almashganda ular
 * bir-biriga zid tushmaydi.
 */
public class YuzGramBarAutoHide {

    /** Bar yashirinishi uchun kerak bo'lgan masofa. */
    private static final float HIDE_THRESHOLD_DP = 16f;
    /** Bar qaytishi uchun yetarli masofa (yashirinishdan sezilarli qisqa). */
    private static final float SHOW_THRESHOLD_DP = 10f;

    private final BaseFragment owner;
    private float accumulated;

    public YuzGramBarAutoHide(@NonNull BaseFragment owner) {
        this.owner = owner;
    }

    /**
     * @param dy pikselda; musbat = kontent yuqoriga (foydalanuvchi ro'yxatni
     *           pastga suryapti), manfiy = teskarisi.
     */
    public void onScrollDelta(float dy) {
        if (dy == 0f) {
            return;
        }
        LaunchActivity la = launch();
        if (la == null) {
            return;
        }
        boolean hidden = la.isYuzGramBarScrollHidden();
        // Yo'nalish o'zgasa — hisobni shu kahondan qayta boshlaymiz, aks holda
        // uzun pastga skroldan keyin tepaga qaytish juda uzoq bo'lardi.
        if ((dy > 0f) == (accumulated > 0f)) {
            accumulated += dy;
        } else {
            accumulated = dy;
        }
        float threshold = (float) AndroidUtilities.dp(hidden ? SHOW_THRESHOLD_DP : HIDE_THRESHOLD_DP);
        if (!hidden && accumulated >= threshold) {
            apply(la, true);
        } else if (hidden && accumulated <= -threshold) {
            apply(la, false);
        }
    }

    /** Tepaga yetganda (yoki ekranga kirganda) barni majburan ko'rsatadi. */
    public void forceShow() {
        accumulated = 0f;
        LaunchActivity la = launch();
        if (la != null && la.isYuzGramBarScrollHidden()) {
            apply(la, false);
        }
    }

    private void apply(LaunchActivity la, boolean hidden) {
        accumulated = 0f;
        la.setYuzGramBarScrollHidden(owner, hidden);
    }

    private LaunchActivity launch() {
        return owner.getParentActivity() instanceof LaunchActivity
                ? (LaunchActivity) owner.getParentActivity() : null;
    }

    /** RecyclerView (chatlar ro'yxati, reels pager, elonlar feed'i) uchun ulagich. */
    public static YuzGramBarAutoHide attachTo(@NonNull RecyclerView rv, @NonNull BaseFragment owner) {
        YuzGramBarAutoHide controller = new YuzGramBarAutoHide(owner);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                controller.onScrollDelta(dy);
                if (!recyclerView.canScrollVertically(-1)) {
                    controller.forceShow();
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(-1)) {
                    controller.forceShow();
                }
            }
        });
        return controller;
    }

    /** NestedScrollView (Funksiyalar hub, Profil) uchun ulagich. */
    public static YuzGramBarAutoHide attachTo(@NonNull NestedScrollView sv, @NonNull BaseFragment owner) {
        YuzGramBarAutoHide controller = new YuzGramBarAutoHide(owner);
        sv.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    controller.onScrollDelta(scrollY - oldScrollY);
                    if (scrollY <= 0) {
                        controller.forceShow();
                    }
                });
        return controller;
    }
}
