package com.thorqol.shared;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Controller/touch update UI is reachable even before login or after Trial expiry. */
public final class AppUpdateActivity extends Activity {
    private AppUpdates updates;private TextView status,version,notes,progressText;private ProgressBar progress;
    private Button check,download,install,cancel,back;private View armed;private boolean held,offered;private int direction;private long next;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable repeat=new Runnable(){public void run(){if(!hasWindowFocus()||direction==0)return;long now=SystemClock.uptimeMillis();if(now>=next){move(direction);next=now+150;}ui.postDelayed(this,20);}};
    private String t(String vi,String en){return "en".equals(LanguagePrompt.language(this))?en:vi;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(10));if(border!=0)d.setStroke(dp(2),border);return d;}
    private TextView text(String copy,int size,int color){TextView v=new TextView(this);v.setText(copy);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(10));return v;}
    private Button button(LinearLayout parent,String copy,Runnable action){Button b=new Button(this);b.setText(copy);b.setAllCaps(false);b.setTextColor(0xff211f30);b.setTextSize(15);b.setTypeface(null,Typeface.BOLD);b.setBackground(bg(0xffe4f43c,0));b.setOnClickListener(v->action.run());b.setOnFocusChangeListener((v,f)->{b.setBackground(bg(f?0xffc2fff0:0xffe4f43c,f?0xffff4795:0));if(android.animation.ValueAnimator.areAnimatorsEnabled())b.animate().scaleX(f?.985f:1).scaleY(f?.985f:1).setDuration(130).start();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.setMargins(dp(3),dp(4),dp(3),dp(6));parent.addView(b,lp);return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(1024,1024);updates=new AppUpdates(this,this::refresh);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(25),dp(12),dp(25),dp(10));root.setBackgroundColor(0xff102f3b);setContentView(root);
        TextView title=text(t("CẬP NHẬT ỨNG DỤNG","APP UPDATE"),25,0xffe4f43c);title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD_ITALIC));root.addView(title);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(22),dp(15),dp(22),dp(16));body.setBackground(bg(0xff211f30,0));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(text(getApplicationInfo().loadLabel(getPackageManager())+"  ·  "+updates.installedVersion()+"  ·  "+(getPackageName().endsWith(".trial")?"TRIAL":"FULL"),17,0xfff7ffff));
        version=text("",19,0xffe4f43c);body.addView(version);status=text("",15,0xfff7ffff);body.addView(status);notes=text("",14,0xffb4ccd0);body.addView(notes);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe4f43c));body.addView(progress,new LinearLayout.LayoutParams(-1,dp(13)));progressText=text("",13,0xffb4ccd0);body.addView(progressText);
        check=button(body,t("Kiểm tra lại","Check again"),updates::check);download=button(body,t("Tải bản cập nhật","Download update"),updates::download);install=button(body,t("Cài cập nhật","Install update"),()->updates.install(this));cancel=button(body,t("Hủy tải","Cancel download"),updates::cancel);back=button(body,t("Quay lại","Back"),this::finish);
        body.addView(text(t("Cài đè giữ dữ liệu và thiết lập. Android sẽ yêu cầu bạn xác nhận cài đặt; không cần gỡ app.","Installing over this app preserves data and settings. Android asks you to confirm installation; keep the app installed."),13,0xffb4ccd0));
        root.addView(text(t("D-pad / cần trái: di chuyển  ·  Nhả A: chọn  ·  B: quay lại","D-pad / left stick: move  ·  Release A: select  ·  B: back"),12,0xffb4ccd0));refresh();updates.check();
    }
    private void refresh(){if(check==null||isFinishing())return;status.setText(updates.message);version.setText(updates.version.isEmpty()?"":t("Phiên bản mới: ","Latest version: ")+updates.version);notes.setText(updates.notes);
        int state=updates.state;boolean transferring=state==AppUpdates.DOWNLOADING||state==AppUpdates.VERIFYING;
        progress.setVisibility(transferring?View.VISIBLE:View.GONE);progressText.setVisibility(transferring?View.VISIBLE:View.GONE);progress.setIndeterminate(state==AppUpdates.VERIFYING||updates.total<=0);if(updates.total>0){progress.setProgress((int)Math.min(1000,updates.done*1000/updates.total),true);progressText.setText(String.format(java.util.Locale.ROOT,"%.1f / %.1f MB",updates.done/1048576d,updates.total/1048576d));}
        check.setEnabled(!updates.busy()&&state!=AppUpdates.READY);download.setVisibility(state==AppUpdates.AVAILABLE?View.VISIBLE:View.GONE);install.setVisibility(state==AppUpdates.READY?View.VISIBLE:View.GONE);cancel.setVisibility(state==AppUpdates.DOWNLOADING?View.VISIBLE:View.GONE);
        if(!offered&&state==AppUpdates.AVAILABLE){offered=true;download.requestFocusFromTouch();}else if(getCurrentFocus()==null)back.requestFocusFromTouch();
    }
    private void move(int key){long now=SystemClock.uptimeMillis();super.dispatchKeyEvent(new KeyEvent(now,now,0,key,0));super.dispatchKeyEvent(new KeyEvent(now,now,1,key,0));}
    @Override public boolean dispatchKeyEvent(KeyEvent e){int key=e.getKeyCode();if(key==KeyEvent.KEYCODE_BUTTON_A||key==KeyEvent.KEYCODE_DPAD_CENTER){if(e.getAction()==0&&e.getRepeatCount()==0){held=true;armed=getCurrentFocus();}if(e.getAction()==1){View target=armed;boolean click=held&&!e.isCanceled()&&target!=null&&target==getCurrentFocus();held=false;armed=null;if(click&&target.isEnabled())target.performClick();}return true;}if(key==KeyEvent.KEYCODE_BUTTON_B){if(e.getAction()==1&&!e.isCanceled())finish();return true;}return super.dispatchKeyEvent(e);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_MOVE||!e.isFromSource(InputDevice.SOURCE_JOYSTICK))return super.dispatchGenericMotionEvent(e);float x=e.getAxisValue(0),y=e.getAxisValue(1),m=Math.max(Math.abs(x),Math.abs(y));int value=direction;if(m<.35f)value=0;else if(m>=.55f)value=Math.abs(x)>Math.abs(y)?(x<0?21:22):(y<0?19:20);if(value!=direction){direction=value;ui.removeCallbacks(repeat);if(value!=0){move(value);next=SystemClock.uptimeMillis()+380;ui.postDelayed(repeat,20);}}return true;}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){held=false;armed=null;direction=0;ui.removeCallbacks(repeat);}}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);if(updates!=null)updates.close();super.onDestroy();}
}
