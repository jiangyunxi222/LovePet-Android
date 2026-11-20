package io.bandorilove.lovepet;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

final class WallpaperResultDialog extends Dialog {
    private final View card;

    WallpaperResultDialog(Context context, boolean applied, Runnable viewDesktop) {
        super(context, R.style.WallpaperResultTheme);
        AppTheme colors = new AppTheme(context);
        View shade = getLayoutInflater().inflate(R.layout.wallpaper_result_dialog, null);
        int heading = applied ? R.string.wallpaper_applied_title : R.string.wallpaper_exit_title;
        setContentView(shade);
        setTitle(heading);
        shade.setOnClickListener(view -> cancel());
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card = shade.findViewById(R.id.wallpaper_result_card);
        card.setBackground(rounded(colors.surface, colors.line, 26));
        shade.findViewById(R.id.wallpaper_result_divider).setBackgroundColor(colors.line);
        TextView title = shade.findViewById(R.id.wallpaper_result_heading);
        title.setText(heading);
        title.setTextColor(colors.ink);
        TextView detail = shade.findViewById(R.id.wallpaper_result_detail);
        detail.setText(applied ? R.string.wallpaper_applied_message : R.string.wallpaper_exit_message);
        detail.setTextColor(colors.ink);

        TextView cancel = shade.findViewById(R.id.wallpaper_result_cancel);
        cancel.setTextColor(colors.ink);
        cancel.setBackground(button(colors.surface, colors.line));
        cancel.setOnClickListener(view -> dismiss());
        TextView desktop = shade.findViewById(R.id.wallpaper_result_desktop);
        desktop.setTextColor(Color.WHITE);
        desktop.setBackground(button(colors.accent, colors.accent));
        desktop.setOnClickListener(view -> {
            dismiss();
            viewDesktop.run();
        });

        shade.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int horizontal = dp(16) + Math.max(safe.left, safe.right);
            int vertical = dp(16) + Math.max(safe.top, safe.bottom);
            view.setPadding(horizontal, vertical, horizontal, vertical);
            return insets;
        });
        shade.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - view.getPaddingLeft() - view.getPaddingRight();
            int width = Math.min(dp(436), available);
            ViewGroup.LayoutParams params = card.getLayoutParams();
            if (width > 0 && params.width != width) {
                params.width = width;
                card.setLayoutParams(params);
            }
        });
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
    }

    @Override public void show() {
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        card.setAlpha(0f);
        card.setTranslationY(dp(8));
        card.setScaleX(.985f);
        card.setScaleY(.985f);
        super.show();
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    private GradientDrawable rounded(int fill, int line, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(1), line);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable button(int fill, int line) {
        return new RippleDrawable(ColorStateList.valueOf(0x2233192f), rounded(fill, line, 999), null);
    }

    private int dp(int size) {
        return Math.round(size * getContext().getResources().getDisplayMetrics().density);
    }
}
