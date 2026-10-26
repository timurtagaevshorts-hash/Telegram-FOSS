package uz.kodlikino.top4.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.YuzChatWallpaper;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

/**
 * YuzGram: bitta chat uchun fon rasmi oynasi. Rasm tanlanadi, shaffoflik
 * sudrab belgilanadi — sudrash paytida ichki namunada (chat ko'rinishida)
 * va orqadagi haqiqiy chatda jonli ko'rsatiladi.
 */
public class YuzChatWallpaperDialog {

    public static final int REQUEST_PICK = 509;

    /** Chat oynasi bilan jonli aloqa: shaffoflik namunasi va yakuniy holat. */
    public interface Host {
        void onOpacityPreview(long dialogId, int opacity);
        void onChanged(long dialogId);
    }

    private static YuzChatWallpaperDialog current;

    private final BaseFragment fragment;
    private final long dialogId;
    private final Host host;
    private AlertDialog dialog;
    private ImageView preview;
    private TextView previewEmpty;
    private TextView opacityLabel;
    private TextView pickButton;

    private int originalOpacity;
    private boolean keepOpacity;

    private YuzChatWallpaperDialog(BaseFragment fragment, long dialogId, Host host) {
        this.fragment = fragment;
        this.dialogId = dialogId;
        this.host = host;
    }

    public static void show(BaseFragment fragment, long dialogId, Host host) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (current != null) {
            current.dismiss();
        }
        current = new YuzChatWallpaperDialog(fragment, dialogId, host);
        current.open();
    }

    /** ChatActivity natijani shu yerga uzatadi; true — natija ishlatildi. */
    public static boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (current == null || requestCode != REQUEST_PICK) {
            return false;
        }
        current.onPicked(resultCode, data);
        return true;
    }

    public static boolean isOpened() {
        return current != null;
    }

    private void open() {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        originalOpacity = YuzChatWallpaper.getOpacity(dialogId);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), 0);

        FrameLayout previewBox = new FrameLayout(context);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(AndroidUtilities.dp(18));
        boxBg.setColor(0xFF0E1621);
        boxBg.setStroke(AndroidUtilities.dp(1), 0x33FFFFFF);
        previewBox.setBackground(boxBg);
        previewBox.setClipToOutline(true);
        previewBox.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        AndroidUtilities.dp(18));
            }
        });
        content.addView(previewBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 172));

        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewBox.addView(preview, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout sample = new LinearLayout(context);
        sample.setOrientation(LinearLayout.VERTICAL);
        sample.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        sample.setGravity(Gravity.BOTTOM);
        previewBox.addView(sample, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView incoming = sampleBubble(context, LocaleController.getString(R.string.yuz_wallpaper_sample_in),
                0xE61D2735, 0xFFE6EEF8);
        sample.addView(incoming, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        TextView outgoing = sampleBubble(context, LocaleController.getString(R.string.yuz_wallpaper_sample_out),
                0xFF2AABEE, 0xFFFFFFFF);
        LinearLayout.LayoutParams outgoingLp = LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT);
        outgoingLp.topMargin = AndroidUtilities.dp(6);
        sample.addView(outgoing, outgoingLp);

        previewEmpty = new TextView(context);
        previewEmpty.setText(LocaleController.getString(R.string.yuz_wallpaper_none));
        previewEmpty.setTextColor(0xFFDCE8F6);
        previewEmpty.setTextSize(13);
        previewEmpty.setGravity(Gravity.CENTER);
        GradientDrawable emptyBg = new GradientDrawable();
        emptyBg.setCornerRadius(AndroidUtilities.dp(12));
        emptyBg.setColor(0x990B1623);
        previewEmpty.setBackground(emptyBg);
        previewEmpty.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6),
                AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        previewBox.addView(previewEmpty, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        pickButton = new TextView(context);
        pickButton.setText(LocaleController.getString(R.string.yuz_wallpaper_pick));
        pickButton.setTextColor(0xFFE8F6FF);
        pickButton.setTextSize(14);
        pickButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        pickButton.setGravity(Gravity.CENTER);
        GradientDrawable pickBg = new GradientDrawable();
        pickBg.setCornerRadius(AndroidUtilities.dp(19));
        pickBg.setColor(0x2E38B6FF);
        pickBg.setStroke(AndroidUtilities.dp(1), 0x9938B6FF);
        pickButton.setBackground(pickBg);
        pickButton.setOnClickListener(v -> pickImage());
        LinearLayout.LayoutParams pickLp = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 40);
        pickLp.topMargin = AndroidUtilities.dp(14);
        content.addView(pickButton, pickLp);

        opacityLabel = new TextView(context);
        opacityLabel.setTextColor(0xFFA9C0D8);
        opacityLabel.setTextSize(13);
        LinearLayout.LayoutParams labelLp = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        labelLp.topMargin = AndroidUtilities.dp(16);
        content.addView(opacityLabel, labelLp);

        SeekBar opacityBar = new SeekBar(context);
        opacityBar.setMax(100 - YuzChatWallpaper.MIN_OPACITY);
        opacityBar.setProgress(originalOpacity - YuzChatWallpaper.MIN_OPACITY);
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + YuzChatWallpaper.MIN_OPACITY;
                updateOpacityLabel(value);
                applyPreviewAlpha(value);
                if (host != null) {
                    host.onOpacityPreview(dialogId, value);
                }
                if (fromUser) {
                    YuzChatWallpaper.setOpacity(dialogId, value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        LinearLayout.LayoutParams barLp = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        barLp.topMargin = AndroidUtilities.dp(6);
        content.addView(opacityBar, barLp);

        updateOpacityLabel(originalOpacity);
        loadPreviewAsync();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.yuz_wallpaper_title));
        builder.setMessage(LocaleController.getString(R.string.yuz_wallpaper_chat_desc));
        builder.setView(content);
        builder.setPositiveButton(LocaleController.getString(R.string.yuz_wallpaper_save), (d, which) -> {
            keepOpacity = true;
            if (host != null) {
                host.onChanged(dialogId);
            }
            Toast.makeText(context, R.string.yuz_wallpaper_applied, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> { });
        if (YuzChatWallpaper.isActive(dialogId)) {
            builder.setNeutralButton(LocaleController.getString(R.string.yuz_wallpaper_clear), (d, which) -> {
                YuzChatWallpaper.clear(dialogId);
                if (preview != null) {
                    preview.setImageDrawable(null);
                }
                if (previewEmpty != null) {
                    previewEmpty.setVisibility(View.VISIBLE);
                }
                if (host != null) {
                    host.onChanged(dialogId);
                }
                Toast.makeText(context, R.string.yuz_wallpaper_cleared, Toast.LENGTH_SHORT).show();
            });
        }
        dialog = builder.create();
        boolean shown = fragment.showDialog(dialog, d -> cleanup()) != null;
        if (!shown) {
            cleanup();
        }
    }

    private TextView sampleBubble(Context context, String text, int bgColor, int textColor) {
        TextView bubble = new TextView(context);
        bubble.setText(text);
        bubble.setTextColor(textColor);
        bubble.setTextSize(13);
        bubble.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6),
                AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(13));
        bg.setColor(bgColor);
        bubble.setBackground(bg);
        return bubble;
    }

    private void cleanup() {
        if (current == this) {
            current = null;
        }
        if (!keepOpacity) {
            int now = YuzChatWallpaper.getOpacity(dialogId);
            if (now != originalOpacity) {
                YuzChatWallpaper.setOpacity(dialogId, originalOpacity);
                if (host != null) {
                    host.onOpacityPreview(dialogId, originalOpacity);
                }
            }
        }
        preview = null;
        previewEmpty = null;
        opacityLabel = null;
        pickButton = null;
        dialog = null;
    }

    private void applyPreviewAlpha(int value) {
        if (preview != null) {
            preview.setAlpha(value / 100f);
        }
    }

    private void updateOpacityLabel(int value) {
        if (opacityLabel != null) {
            opacityLabel.setText(LocaleController.formatString(
                    "yuz_wallpaper_opacity", R.string.yuz_wallpaper_opacity, value));
        }
    }

    private void loadPreviewAsync() {
        final int alpha = YuzChatWallpaper.getOpacity(dialogId);
        Utilities.globalQueue.postRunnable(() -> {
            final Bitmap bitmap = YuzChatWallpaper.isActive(dialogId)
                    ? YuzChatWallpaper.getBitmap(dialogId) : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (dialog == null) {
                    return;
                }
                showPreview(bitmap, alpha);
            });
        });
    }

    private void showPreview(Bitmap bitmap, int alpha) {
        if (preview == null) {
            return;
        }
        preview.setImageBitmap(bitmap);
        preview.setAlpha(alpha / 100f);
        if (previewEmpty != null) {
            previewEmpty.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            fragment.startActivityForResult(
                    Intent.createChooser(intent,
                            LocaleController.getString(R.string.yuz_wallpaper_pick)),
                    REQUEST_PICK);
        } catch (Throwable e) {
            Toast.makeText(fragment.getParentActivity(),
                    R.string.yuz_wallpaper_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void onPicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Uri uri = data.getData();
        if (pickButton != null) {
            pickButton.setEnabled(false);
            pickButton.setText(LocaleController.getString(R.string.yuz_wallpaper_loading));
        }
        Utilities.globalQueue.postRunnable(() -> {
            final Bitmap bitmap = YuzChatWallpaper.importImage(
                    context.getContentResolver(), uri, dialogId);
            AndroidUtilities.runOnUIThread(() -> {
                if (pickButton != null) {
                    pickButton.setEnabled(true);
                    pickButton.setText(LocaleController.getString(R.string.yuz_wallpaper_pick));
                }
                // Oyna yopilgan bo'lsa ham rasm saqlangan — chat qatlami yangilanishi shart.
                if (bitmap != null) {
                    showPreview(bitmap, YuzChatWallpaper.getOpacity(dialogId));
                    if (host != null) {
                        host.onChanged(dialogId);
                    }
                    Toast.makeText(context, R.string.yuz_wallpaper_applied, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.yuz_wallpaper_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
        }
    }
}
