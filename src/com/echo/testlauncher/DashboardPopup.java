package com.echo.testlauncher;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared modal panel: a titled card with a scrolling body and an action row. */
final class DashboardPopup extends Dialog {

    static final int INK = 0xfff8f5ff;
    static final int MUTED = 0xffc1bdce;
    static final int MUSIC = 0xff392c49;
    static final int SHOPPING = 0xff253d39;
    static final int MUSIC_ACCENT = 0xffdfbaf8;
    static final int SHOP_ACCENT = 0xffa5e5ce;

    private static final int RIPPLE_COLOR = 0x28ffffff;
    private static final int CORNER_RADIUS_DP = 12;
    private static final int BUTTON_HEIGHT_DP = 56;
    private static final int MAX_WIDTH_DP = 720;
    private static final int EDGE_MARGIN_DP = 48;
    private static final int EDGE_MARGIN_BOTTOM_DP = 80;
    private static final float DIM_AMOUNT = 0.58f;

    final LinearLayout panel;
    final LinearLayout header;
    final LinearLayout body;
    final LinearLayout actions;
    final TextView title;
    final int accent;

    private final float density;

    DashboardPopup(Context context, String name, int color, int accent) {
        super(context);
        this.accent = accent;
        this.density = context.getResources().getDisplayMetrics().density;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        panel = column();
        panel.setPadding(dp(28), dp(24), dp(28), dp(24));
        panel.setBackground(shape(color, 24));

        header = row();
        title = label(name, 28, accent, true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(
                button("×", accent, Color.TRANSPARENT, this::dismiss),
                new LinearLayout.LayoutParams(dp(56), dp(56)));
        panel.addView(header);
        gap(panel, 16);

        body = column();
        panel.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        actions = row();
        panel.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(panel);
        setCanceledOnTouchOutside(true);
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    TextView label(String text, int sizeSp, int color, boolean medium) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    void gap(LinearLayout parent, int heightDp) {
        parent.addView(new View(getContext()),
                new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    Button button(String text, int foreground, int background, Runnable click) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(22);
        button.setTextColor(foreground);
        button.setMinHeight(dp(BUTTON_HEIGHT_DP));
        button.setMinimumHeight(dp(BUTTON_HEIGHT_DP));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE_COLOR),
                shape(background, CORNER_RADIUS_DP),
                shape(Color.WHITE, CORNER_RADIUS_DP)));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    void open(boolean showKeyboard, int desiredHeightDp) {
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(DIM_AMOUNT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (showKeyboard
                        ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
        show();

        DisplayMetrics metrics = new DisplayMetrics();
        window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        window.setLayout(
                Math.min(dp(MAX_WIDTH_DP), metrics.widthPixels - dp(EDGE_MARGIN_DP)),
                Math.min(dp(desiredHeightDp), metrics.heightPixels - dp(EDGE_MARGIN_BOTTOM_DP)));
    }
}
