package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class YuzCrashRecoveryActivity extends Activity {

    private TextView reportView;
    private String report;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding + insets.getSystemWindowInsetLeft(),
                    padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(),
                    padding + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("YuzGram diagnostika");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        content.addView(title);

        TextView instructions = new TextView(this);
        instructions.setText("YuzGramni oching. Yana yopilsa, shu oynaga qayting va logni Telegram orqali yuboring. Log faqat telefonda saqlanadi.");
        instructions.setTextColor(Color.WHITE);
        instructions.setTextSize(16);
        instructions.setPadding(0, padding, 0, padding);
        content.addView(instructions);

        Button open = new Button(this);
        open.setText("YuzGramni ochish");
        open.setOnClickListener(view -> {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
            startActivity(intent);
        });
        content.addView(open);

        Button refresh = new Button(this);
        refresh.setText("Logni yangilash");
        refresh.setOnClickListener(view -> refreshReport());
        content.addView(refresh);

        Button share = new Button(this);
        share.setText("Logni ulashish");
        share.setOnClickListener(view -> {
            refreshReport();
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(intent, "Crash logini yuborish"));
        });
        content.addView(share);

        reportView = new TextView(this);
        reportView.setTextColor(Color.WHITE);
        reportView.setTextSize(14);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(0, padding, 0, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(reportView);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReport();
    }

    private void refreshReport() {
        StringBuilder text = new StringBuilder("YuzGram startup diagnostic\n");
        try {
            text.append("version=").append(getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName).append('\n');
        } catch (Exception error) {
            text.append("Version unavailable: ").append(error).append('\n');
        }
        text.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" target=").append(getApplicationInfo().targetSdkVersion)
                .append(" device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append("\n\n")
                .append(YuzCrashPostman.read(this));
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                        getPackageName(), 0, 8);
                text.append("\n\nAndroid process exits:\n");
                for (ApplicationExitInfo exit : exits) {
                    if (!getPackageName().equals(exit.getProcessName())) {
                        continue;
                    }
                    text.append("time=").append(exit.getTimestamp())
                            .append(" reason=").append(exit.getReason())
                            .append(" status=").append(exit.getStatus())
                            .append(" description=").append(exit.getDescription())
                            .append('\n');
                }
            } catch (Exception error) {
                text.append("\nExit info unavailable: ").append(error);
            }
        }
        report = text.toString();
        reportView.setText(report);
    }
}
