package uz.kodlikino.top4.ui;

import android.widget.FrameLayout;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.ArrayList;

/**
 * YuzGram #70: storiya kirish nuqtalari — chatdan olib tashlangan storylar
 * qatori o'rniga Reels tepasidagi tugmalar shu yerdan foydalanadi.
 * Telegram'ning o'z StoryRecorder/StoryViewer'iga yupqa qatlam.
 */
public final class YuzStories {

    private YuzStories() {
    }

    /** "Hikoya joylash" — cheklov tekshirib, rekordchini ochadi. */
    public static void openRecorder(BaseFragment fragment, FrameLayout anchor) {
        if (fragment == null || fragment.getParentActivity() == null || anchor == null) {
            return;
        }
        int account = UserConfig.selectedAccount;
        StoriesController controller = MessagesController.getInstance(account).getStoriesController();
        StoriesController.StoryLimit limit = controller.checkStoryLimit();
        if (limit != null) {
            fragment.showDialog(new LimitReachedBottomSheet(
                    fragment, fragment.getContext(), limit.getLimitReachedType(), account, null));
            return;
        }
        StoryRecorder.getInstance(fragment.getParentActivity(), account)
                .open(StoryRecorder.SourceView.fromFloatingButton(anchor));
    }

    /**
     * "Hikoyalar" — barcha faol storiya egalarini navbat bilan ochadi.
     * @return true agar ochilgan bo'lsa (bo'sh bo'lsa false).
     */
    public static boolean viewAll(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        int account = UserConfig.selectedAccount;
        StoriesController controller = MessagesController.getInstance(account).getStoriesController();
        controller.checkExpiredStories();
        long selfId = UserConfig.getInstance(account).clientUserId;
        ArrayList<Long> peerIds = new ArrayList<>();
        if (controller.hasSelfStories()) {
            peerIds.add(selfId);
        }
        ArrayList<TL_stories.PeerStories> list = controller.getDialogListStories();
        if (list != null) {
            for (TL_stories.PeerStories ps : list) {
                // #120: noto'g'ri/buzilgan peer butun "Hikoyalar" ko'rishni
                // yiqitmasligi kerak — har bir peer alohida himoyalangan
                // (YuzStoryFeed.buildFeed'dagi kabi).
                try {
                    long dialogId = DialogObject.getPeerDialogId(ps.peer);
                    if (dialogId != selfId && controller.hasStories(dialogId)) {
                        peerIds.add(dialogId);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        if (peerIds.isEmpty()) {
            return false;
        }
        StoryViewer viewer = fragment.getOrCreateStoryViewer();
        viewer.open(fragment.getContext(), null, peerIds, 0, null, null, null, false);
        return true;
    }
}
