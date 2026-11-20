package io.bandorilove.lovepet;

import android.app.Activity;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public final class WallpaperSettingsActivity extends Activity {
    private boolean openingPermissionEditor;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved == null && usesXiaomiPermissions() && open(new Intent("miui.intent.action.APP_PERM_EDITOR")
                .setPackage("com.miui.securitycenter")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("extra_pkgname", getPackageName()))) {
            openingPermissionEditor = true;
            getWindow().getDecorView().postDelayed(() -> {
                if (!openingPermissionEditor || isFinishing() || isDestroyed()) return;
                openingPermissionEditor = false;
                showSettingsHelp();
            }, 1500);
        } else {
            showSettingsHelp();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (openingPermissionEditor) finish();
    }

    static boolean usesXiaomiPermissions() {
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                || "Xiaomi".equalsIgnoreCase(Build.BRAND)
                || "Redmi".equalsIgnoreCase(Build.BRAND)
                || "POCO".equalsIgnoreCase(Build.BRAND);
    }

    private void showSettingsHelp() {
        WallpaperManager manager = WallpaperManager.getInstance(this);
        int message = !manager.isWallpaperSupported() ? R.string.wallpaper_help_unsupported
                : !manager.isSetWallpaperAllowed() ? R.string.wallpaper_help_restricted
                : R.string.wallpaper_help_no_permission_page;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.wallpaper_settings_dialog);
        dialog.setTitle(R.string.wallpaper_settings);
        dialog.findViewById(R.id.wallpaper_dialog_card).setBackground(
                rounded(R.color.wallpaper_dialog_surface, R.color.wallpaper_dialog_line, 26));
        TextView body = dialog.findViewById(R.id.wallpaper_dialog_message);
        body.setText(message);
        Button picker = dialog.findViewById(R.id.wallpaper_dialog_picker);
        Button appInfo = dialog.findViewById(R.id.wallpaper_dialog_app_info);
        Button cancel = dialog.findViewById(R.id.wallpaper_dialog_cancel);
        styleButton(picker, true);
        styleButton(appInfo, false);
        styleButton(cancel, false);
        dialog.setOnDismissListener(ignored -> {
            if (!isChangingConfigurations()) finish();
        });
        picker.setOnClickListener(view -> {
            if (new ComponentName(this, MainActivity.class).equals(getCallingActivity())) {
                setResult(RESULT_OK);
                dialog.dismiss();
            } else if (open(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                    || open(new Intent(Intent.ACTION_SET_WALLPAPER))) dialog.dismiss();
            else body.setText(R.string.wallpaper_help_manual_picker);
        });
        appInfo.setOnClickListener(view -> {
            if (open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)))) dialog.dismiss();
            else body.setText(R.string.wallpaper_help_manual_app_info);
        });
        cancel.setOnClickListener(view -> dialog.dismiss());
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.33f);
        dialog.show();
        int width = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        window.setLayout(Math.min(width - dp(32), dp(436)), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void styleButton(Button button, boolean primary) {
        int color = primary ? R.color.wallpaper_dialog_accent : R.color.wallpaper_dialog_surface;
        int border = primary ? R.color.wallpaper_dialog_accent : R.color.wallpaper_dialog_line;
        button.setTextColor(primary ? Color.WHITE : getColor(R.color.wallpaper_dialog_ink));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22432C42), rounded(color, border, 24), null));
    }

    private GradientDrawable rounded(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getColor(fill));
        shape.setStroke(dp(1), getColor(border));
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private boolean open(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
}
