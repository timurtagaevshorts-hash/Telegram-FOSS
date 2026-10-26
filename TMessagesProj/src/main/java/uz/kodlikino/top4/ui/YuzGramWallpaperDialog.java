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
import org.telegram.messenger.YuzWallpaper;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

/**
 * YuzGram #193: "Fon rasmi" oynasi — rasm tanlash, shaffoflikni belgilash
 * (jonli ko'rinish) va saqlash. Tanlangan rasm ilova foniga darhol o'tadi,
 * "O'chirish" esa uni butunlay olib tashlaydi.
 */
public class YuzGramWallpaperDialog {

    public static final int REQUEST_PICK = 508;

    private static YuzGramWallpaperDialog current;

    private final BaseFragment fragment;
    private AlertDialog dialog;
    private ImageView preview;
    private TextView emptyView;
    private TextView opacityLabel;
    private TextView pickButton;

    private YuzGramWallpaperDialog(BaseFragment fragment) {
        this.fragment = fragment;
    }

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (current != null) {
            current.dismiss();
        }
        current = new YuzGramWallpaperDialog(fragment);
        current.open();
    }

    /** LaunchActivity natijani shu yerga uzatadi; true — natija ishlatildi. */
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

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), 0);

        FrameLayout previewBox = new FrameLayout(context);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(AndroidUtilities.dp(16));
        boxBg.setColor(0x14FFFFFF);
        boxBg.setStroke(AndroidUtilities.dp(1), 0x33FFFFFF);
        previewBox.setBackground(boxBg);
        content.addView(previewBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150));

        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        AndroidUtilities.dp(16));
            }
        });
        previewBox.addView(preview, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.yuz_wallpaper_none));
        emptyView.setTextColor(0xFF8794AF);
        emptyView.setTextSize(13);
        emptyView.setGravity(Gravity.CENTER);
        previewBox.addView(emptyView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

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
        opacityLabel.setTextColor(0xFF8794AF);
        opacityLabel.setTextSize(13);
        LinearLayout.LayoutParams labelLp = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        labelLp.topMargin = AndroidUtilities.dp(16);
        content.addView(opacityLabel, labelLp);

        SeekBar opacityBar = new SeekBar(context);
        opacityBar.setMax(100 - YuzWallpaper.MIN_OPACITY);
        opacityBar.setProgress(YuzWallpaper.getOpacity() - YuzWallpaper.MIN_OPACITY);
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + YuzWallpaper.MIN_OPACITY;
                updateOpacityLabel(value);
                if (preview != null) {
                    preview.setAlpha(value / 100f);
                }
                if (fromUser) {
                    YuzWallpaper.setOpacity(value);
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

        updateOpacityLabel(YuzWallpaper.getOpacity());
        updatePreviewImage();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.yuz_wallpaper_title));
        builder.setMessage(LocaleController.getString(R.string.yuz_wallpaper_desc));
        builder.setView(content);
        builder.setPositiveButton(LocaleController.getString(R.string.yuz_wallpaper_save), (d, which) -> {
            if (YuzWallpaper.isActive()) {
                Toast.makeText(context, R.string.yuz_wallpaper_applied, Toast.LENGTH_SHORT).show();
            }
            dismiss();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> dismiss());
        if (YuzWallpaper.isActive()) {
            builder.setNeutralButton(LocaleController.getString(R.string.yuz_wallpaper_clear), (d, which) -> {
                YuzWallpaper.clear();
                updatePreviewImage();
                Toast.makeText(context, R.string.yuz_wallpaper_cleared, Toast.LENGTH_SHORT).show();
            });
        }
        dialog = builder.create();
        boolean shown = fragment.showDialog(dialog, d -> cleanup()) != null;
        if (!shown) {
            cleanup();
        }
    }

    private void cleanup() {
        if (current == this) {
            current = null;
        }
        preview = null;
        emptyView = null;
        opacityLabel = null;
        pickButton = null;
        dialog = null;
    }

    private void updateOpacityLabel(int value) {
        if (opacityLabel != null) {
            opacityLabel.setText(LocaleController.formatString(
                    "yuz_wallpaper_opacity", R.string.yuz_wallpaper_opacity, value));
        }
    }

    private void updatePreviewImage() {
        if (preview == null) {
            return;
        }
        Bitmap bitmap = YuzWallpaper.isActive() ? YuzWallpaper.getBitmap() : null;
        preview.setImageBitmap(bitmap);
        preview.setAlpha(YuzWallpaper.getOpacity() / 100f);
        if (emptyView != null) {
            emptyView.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
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
            final boolean ok = YuzWallpaper.importImage(context.getContentResolver(), uri);
            AndroidUtilities.runOnUIThread(() -> {
                if (pickButton != null) {
                    pickButton.setEnabled(true);
                    pickButton.setText(LocaleController.getString(R.string.yuz_wallpaper_pick));
                }
                if (dialog == null) {
                    return;
                }
                if (ok) {
                    updatePreviewImage();
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
