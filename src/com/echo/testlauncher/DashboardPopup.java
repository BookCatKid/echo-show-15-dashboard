package com.echo.testlauncher;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

final class DashboardPopup extends Dialog {
    static final int INK=0xfff8f5ff, MUTED=0xffc1bdce, MUSIC=0xff392c49, SHOPPING=0xff253d39,
            MUSIC_ACCENT=0xffdfbaf8, SHOP_ACCENT=0xffa5e5ce;
    final LinearLayout panel, header, body, actions;
    final TextView title;
    final int accent;
    final float density;
    DashboardPopup(Context c,String name,int color,int accent) {
        super(c);this.accent=accent;density=c.getResources().getDisplayMetrics().density;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel=column(); panel.setPadding(dp(28),dp(24),dp(28),dp(24));
        panel.setBackground(shape(color,24));
        header=row(); title=label(name,28,accent,true);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(button("×",accent,Color.TRANSPARENT,()->dismiss()),new LinearLayout.LayoutParams(dp(56),dp(56)));
        panel.addView(header);gap(panel,16);
        body=column();panel.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        actions=row();panel.addView(actions,new LinearLayout.LayoutParams(-1,-2)); setContentView(panel);
        setCanceledOnTouchOutside(true);
    }
    int dp(float n){return Math.round(n*density);}
    LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(1); return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    GradientDrawable shape(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
    TextView label(String s,int size,int c,boolean medium){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(c);t.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",0));return t;}
    void gap(LinearLayout l,int h){l.addView(new View(getContext()),new LinearLayout.LayoutParams(1,dp(h)));}
    Button button(String s,int fg,int bg,Runnable click){Button b=new Button(getContext());b.setAllCaps(false);b.setText(s);b.setTextSize(22);b.setTextColor(fg);b.setMinHeight(dp(56));b.setMinimumHeight(dp(56));b.setPadding(dp(16),0,dp(16),0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff),shape(bg,12),shape(Color.WHITE,12)));b.setOnClickListener(v->click.run());return b;}
    void open(boolean keyboard,int desiredHeight){
        Window w=getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(.58f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | (keyboard?WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
        show(); android.util.DisplayMetrics m=new android.util.DisplayMetrics();w.getWindowManager().getDefaultDisplay().getMetrics(m);
        w.setLayout(Math.min(dp(720),m.widthPixels-dp(48)),Math.min(dp(desiredHeight),m.heightPixels-dp(80)));
    }
}
