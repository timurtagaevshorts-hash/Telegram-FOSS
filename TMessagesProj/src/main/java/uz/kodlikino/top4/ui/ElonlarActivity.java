package uz.kodlikino.top4.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * "Elonlar" (classifieds) bo'limi — YuzGram pastki navigatsiyasining 2-tab'i.
 * Pastki bar LaunchActivity darajasida (doimiy) chiziladi — bu ekran o'z bari'ni qo'shmaydi.
 * Faza B: placeholder. Faza C'da bu yerga eski KinoTOP4 elonlar feed'i ko'chiriladi.
 */
public class ElonlarActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Elonlar");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        TextView placeholder = new TextView(context);
        placeholder.setText("E'lonlar bo'limi\nTez orada...");
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextSize(16);
        placeholder.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        // Pastdagi doimiy bar ostiga tushmasligi uchun joy bo'shatamiz.
        placeholder.setPadding(0, 0, 0, YuzGramBottomBar.height());
        root.addView(placeholder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        return root;
    }
}
