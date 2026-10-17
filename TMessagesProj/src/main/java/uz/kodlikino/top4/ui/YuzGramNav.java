package uz.kodlikino.top4.ui;

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
                if (current instanceof YuzGramProfileActivity) {
                    return;
                }
                // YuzGram profili (Telegram ismi/@username/bio, reel grid,
                // kontent boshqaruvi). Tepadagi gear tugmasi orqali
                // Telegram'ning native ProfileActivity'siga o'tish mumkin.
                int account = current.getCurrentAccount();
                long clientUserId = UserConfig.getInstance(account).getClientUserId();
                String uid = "tg_" + clientUserId;
                current.presentFragment(
                        new YuzGramProfileActivity(uid, "", "", true, false), true);
                break;
        }
    }
}
