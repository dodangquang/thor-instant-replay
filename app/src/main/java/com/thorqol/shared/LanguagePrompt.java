package com.thorqol.shared;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Language is local UI state. Choosing it never starts a trial or changes a license. */
public final class LanguagePrompt {
    private LanguagePrompt(){}
    private static boolean valid(String value){return "vi".equals(value)||"en".equals(value);}
    public static String language(Context c){return c.getSharedPreferences("thorqol_ui",0).getString("language","vi");}
    public static boolean needed(Context c){SharedPreferences p=c.getSharedPreferences("thorqol_ui",0);
        if(p.getBoolean("language_onboarding_done",false)&&valid(p.getString("language",null)))return false;
        String existing=p.getString("language",null);
        if(!valid(existing)&&c.getPackageName().startsWith("com.thor.instantreplay"))existing=c.getSharedPreferences("android_replay_ui",0).getString("language",null);
        if(valid(existing)){select(c,existing);return false;}return true;
    }
    public static void select(Context c,String value){if(!valid(value))throw new IllegalArgumentException("language");
        c.getSharedPreferences("thorqol_ui",0).edit().putString("language",value).putBoolean("language_onboarding_done",true).commit();
        if(c.getPackageName().startsWith("com.thor.instantreplay"))c.getSharedPreferences("android_replay_ui",0).edit().putString("language",value).commit();
    }
    public static void ensure(Activity a,Runnable chosen){if(needed(a))show(a,chosen);else chosen.run();}
    public static android.app.Dialog show(Activity a,Runnable chosen){
        ControllerDialog dialog=new ControllerDialog(a);LinearLayout panel=new LinearLayout(a);panel.setOrientation(1);int padding=dp(a,24);panel.setPadding(padding,padding,padding,padding);panel.setBackground(shape(a,0xff211f30,0xff567582));
        TextView title=new TextView(a);title.setText("CHỌN NGÔN NGỮ / CHOOSE LANGUAGE");title.setTextColor(0xffe4f43c);title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        TextView description=new TextView(a);description.setText("Chọn ngôn ngữ giao diện. Bạn có thể đổi lại trong app.\nChoose your interface language. You can change it later.");description.setTextColor(0xffb4ccd0);description.setTextSize(14);description.setPadding(0,dp(a,12),0,dp(a,20));panel.addView(description);
        LinearLayout row=new LinearLayout(a);row.setOrientation(0);Button first=null;
        for(String value:new String[]{"vi","en"}){Button b=new Button(a);b.setAllCaps(false);b.setText("vi".equals(value)?"TIẾNG VIỆT\nVietnamese":"ENGLISH\nTiếng Anh");b.setTextColor(0xff211f30);b.setTextSize(17);b.setTypeface(null,Typeface.BOLD);b.setBackground(shape(a,0xffe4f43c,0));b.setOnFocusChangeListener((v,focus)->{b.setBackground(shape(a,focus?0xffc2fff0:0xffe4f43c,focus?0xffff4795:0));if(android.animation.ValueAnimator.areAnimatorsEnabled())b.animate().scaleX(focus?.98f:1).scaleY(focus?.98f:1).setDuration(130).start();});
            b.setOnClickListener(v->{select(a,value);dialog.dismiss();chosen.run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(a,78),1);lp.setMargins(dp(a,4),dp(a,4),dp(a,4),dp(a,4));row.addView(b,lp);if(first==null)first=b;}
        panel.addView(row);TextView hint=new TextView(a);hint.setText("D-pad / Left stick: move   ·   Release A: select");hint.setTextColor(0xffb4ccd0);hint.setTextSize(12);hint.setPadding(0,dp(a,15),0,0);panel.addView(hint);
        dialog.setContentView(panel);dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->a.finish());dialog.show();dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels-dp(a,36),dp(a,650)),WindowManager.LayoutParams.WRAP_CONTENT);if(first!=null)first.requestFocusFromTouch();return dialog;
    }
    private static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    private static GradientDrawable shape(Context c,int fill,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(c,12));if(stroke!=0)g.setStroke(dp(c,2),stroke);return g;}
}
