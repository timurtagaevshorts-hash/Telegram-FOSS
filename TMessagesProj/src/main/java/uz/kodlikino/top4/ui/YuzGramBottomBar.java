package uz.kodlikino.top4.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * YuzGram pastki navigatsiya bari: Asosiy / Elonlar / Profil.
 * Har bir root ekran (chats, elonlar) o'z barini qo'shadi va faol tab'ni belgilaydi.
 * Tab bosilganda host {@link OnTabSelectedListener} orqali tegishli root fragment'ga o'tadi.
 */
public class YuzGramBottomBar extends LinearLayout {

    public static final int TAB_ASOSIY = 0;
    public static final int TAB_ELONLAR = 1;
    public static final int TAB_PROFIL = 2;

    public static final int COUNT = 3;

    private static final String[] LABELS = {"Asosiy", "Elonlar", "Profil"};

    private final TextView[] tabs = new TextView[COUNT];
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int activeTab = TAB_ASOSIY;
    private OnTabSelectedListener listener;

    public interface OnTabSelectedListener {
        void onTabSelected(int tab);
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
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        setElevation(AndroidUtilities.dp(12));
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(AndroidUtilities.dp(0.6f));

        for (int i = 0; i < COUNT; i++) {
            final int index = i;
            TextView tab = new TextView(getContext());
            tab.setText(LABELS[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(13);
            tab.setClickable(true);
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onTabSelected(index);
                    }
                }
            });
            tabs[i] = tab;
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            addView(tab, lp);
        }
        applyColors();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float y = dividerPaint.getStrokeWidth() / 2f;
        canvas.drawLine(0, y, getWidth(), y, dividerPaint);
    }

    public void setActiveTab(int tab) {
        if (tab < 0 || tab >= COUNT) {
            return;
        }
        activeTab = tab;
        applyColors();
    }

    public int getActiveTab() {
        return activeTab;
    }

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        this.listener = listener;
    }

    private void applyColors() {
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
        int active = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int inactive = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        for (int i = 0; i < COUNT; i++) {
            tabs[i].setTextColor(i == activeTab ? active : inactive);
            tabs[i].setTypeface(null, i == activeTab ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** Bar balandligi (dp). Host ekranlar list padding'ini shu qadrga oshiradi. */
    public static int height() {
        return AndroidUtilities.dp(56);
    }
}
