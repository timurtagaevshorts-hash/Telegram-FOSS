package uz.kodlikino.top4.ui;

import android.os.Bundle;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ProfileActivity;

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
            case YuzGramBottomBar.TAB_ELONLAR:
                if (current instanceof ElonlarActivity) {
                    return;
                }
                current.presentFragment(new ElonlarActivity(), true);
                break;
            case YuzGramBottomBar.TAB_PROFIL:
                if (current instanceof ProfileActivity) {
                    return;
                }
                int account = current.getCurrentAccount();
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                args.putBoolean("my_profile", true);
                current.presentFragment(new ProfileActivity(args, null), true);
                break;
        }
    }
}
