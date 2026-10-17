package uz.kodlikino.top4.ui;

import android.os.Bundle;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;

/**
 * YuzGram pastki navigatsiya bo'yicha root ekranlar orasida almashish.
 * presentFragment(..., removeLast=true) ishlatiladi — tab'lar orasida
 * back-stack'ni tozalaydi, xuddi standart bottom-nav kabi.
 */
public final class YuzGramNav {

    private YuzGramNav() {
    }

    public static void select(BaseFragment current, int tab) {
        if (current == null) {
            return;
        }
        switch (tab) {
            case YuzGramBottomBar.TAB_ASOSIY:
                if (current instanceof DialogsActivity) {
                    return;
                }
                current.presentFragment(new DialogsActivity(null), true);
                break;
            case YuzGramBottomBar.TAB_REELS:
                if (current instanceof ReelsActivity) {
                    return;
                }
                current.presentFragment(new ReelsActivity(), true);
                break;
            case YuzGramBottomBar.TAB_ELONLAR:
                if (current instanceof FunksiyalarActivity) {
                    return;
                }
                current.presentFragment(new FunksiyalarActivity(), true);
                break;
            case YuzGramBottomBar.TAB_PROFIL:
                if (current instanceof org.telegram.ui.ProfileActivity
                        && ((org.telegram.ui.ProfileActivity) current).myProfile) {
                    return;
                }
                // Faqat native Telegram profil oynasi (o'zimizniki) — Instagram
                // uslubidagi YuzGramProfileActivity endi o'z profil uchun
                // ishlatilmaydi; u faqat begona reel mualliflari profili.
                Bundle profileArgs = new Bundle();
                profileArgs.putLong("user_id", UserConfig.getInstance(current.getCurrentAccount()).getClientUserId());
                profileArgs.putBoolean("my_profile", true);
                current.presentFragment(new org.telegram.ui.ProfileActivity(profileArgs, null), true);
                break;
        }
    }
}
