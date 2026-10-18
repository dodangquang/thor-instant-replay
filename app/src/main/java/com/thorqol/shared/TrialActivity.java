package com.thorqol.shared;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Native trial activation and remaining allowance, with controller A activated on release. */
public final class TrialActivity extends Activity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LinearLayout body;private TextView status;private Button enter;
    private boolean pending,held;private View armed;private int direction;private long nextMove;
    private String message="";private long nextStatus;private Button firstButton;
    private final Runnable tick=new Runnable(){public void run(){if(!hasWindowFocus()||isFinishing())return;long now=SystemClock.uptimeMillis();if(now>=nextStatus){if(status!=null)status.setText(TrialAccess.statusText(TrialActivity.this));if(enter!=null)enter.setEnabled(!pending&&TrialAccess.canUse(TrialActivity.this));nextStatus=now+1000;}if(direction!=0&&now>=nextMove){move(direction);nextMove=now+150;}ui.postDelayed(this,40);}};
    private String t(String vi,String en){return TrialAccess.t(this,vi,en);}
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable background(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(8));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setFlags(1024,1024);render();LanguagePrompt.ensure(this,this::render);}
    private void text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(9));body.addView(v);}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(0xff211f30);b.setTypeface(null,Typeface.BOLD);b.setBackground(background(0xffe4f43c,0));b.setOnClickListener(v->{if(!pending)action.run();});b.setOnFocusChangeListener((v,f)->b.setBackground(background(f?0xffc2fff0:0xffe4f43c,f?0xffff4795:0)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.bottomMargin=dp(10);body.addView(b,p);b.setEnabled(!pending);if(firstButton==null&&b.isEnabled())firstButton=b;return b;}
    private void render(){
        held=false;armed=null;direction=0;firstButton=null;
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff102f3b);root.setPadding(dp(26),dp(14),dp(26),dp(10));setContentView(root);
        TextView title=new TextView(this);title.setText(TrialAccess.label(this)+" / TRIAL · $0");title.setTextSize(23);title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD_ITALIC));title.setTextColor(0xffe4f43c);title.setPadding(0,0,0,dp(10));root.addView(title);
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(20),dp(12),dp(20),dp(8));body.setBackground(background(0xff211f30,0));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setText(TrialAccess.statusText(this));status.setTextSize(18);status.setTextColor(0xffe4f43c);status.setPadding(0,dp(6),0,dp(12));body.addView(status);
        boolean replay="com.thor.instantreplay".equals(TrialAccess.app(this));
        text(replay?t("Dùng Android Replay trong 24 giờ từ lần đầu chọn Bắt đầu dùng thử trên máy này. Làm mới hoặc cài lại app không bắt đầu lại 24 giờ.","Use Android Replay for 24 hours from the first Start trial on this device. Refreshing or reinstalling does not restart the clock."):t("Tải tối đa 3 game khác nhau trên máy này. Tải lại hoặc cập nhật cùng game không tốn thêm lượt. Lượt được tính khi bắt đầu tải một game mới.","Download up to 3 different games on this device. Retrying or updating the same game uses no extra slot. A new game counts when its download begins."),15,0xfff7ffff);
        text(t("Cần mạng để bật dùng thử và bắt đầu lượt tải. Hạn mức được lưu theo máy trên máy chủ; cài lại app hoặc đổi email không làm mới lượt. Dữ liệu đã tải vẫn thuộc về bạn.","Internet is required to activate the trial and start downloads. Allowances are stored per device on the server; reinstalling or changing email does not reset them. Your downloaded files remain yours."),14,0xffb4ccd0);
        if(!message.isEmpty())text(message,14,0xffff4795);
        button(t("Bắt đầu / kiểm tra dùng thử","Start / check trial"),()->run(true));
        button(t("Làm mới hạn mức","Refresh allowance"),()->run(false));
        enter=button(t("Vào ứng dụng","Open app"),this::launch);enter.setEnabled(!pending&&TrialAccess.canUse(this));
        button(t("Mở combo chính · $25 vĩnh viễn / 2 máy","Full bundle · $25 lifetime / 2 devices"),()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(new TrialAccess.Config(this).shop)));}catch(Exception e){message=t("Chưa mở được cửa hàng.","Could not open the store.");render();}});
        button(t("Cập nhật ứng dụng","Update app"),()->AppUpdates.open(this));
        button("Tiếng Việt / English",()->LanguagePrompt.show(this,this::render));
        button(t("Đóng","Close"),this::finish);
        TextView hint=new TextView(this);hint.setText(t("D-pad / cần trái: di chuyển  |  Nhả A: chọn  |  B: đóng","D-pad / left stick: move  |  Release A: select  |  B: close"));hint.setTextColor(0xffb4ccd0);hint.setTextSize(12);hint.setPadding(0,dp(8),0,0);root.addView(hint);
        if(firstButton!=null)firstButton.requestFocusFromTouch();
    }
    private void run(boolean start){if(pending)return;pending=true;message=t("Đang kiểm tra…","Checking…");render();network.execute(()->{try{if(start)TrialAccess.start(this);else TrialAccess.status(this);message="";}catch(Exception e){message=e instanceof TrialAccess.TrialException?e.getMessage():t("Chưa kiểm tra được dùng thử. Hãy kết nối mạng và thử lại.","Could not verify the trial. Connect to the internet and try again.");}finally{ui.post(()->{if(isFinishing()||isDestroyed())return;pending=false;render();if(start&&message.isEmpty()&&TrialAccess.canUse(this))launch();});}});}
    private void launch(){if(!TrialAccess.canUse(this))return;Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(launch);finish();}}
    private void move(int key){long now=SystemClock.uptimeMillis();super.dispatchKeyEvent(new KeyEvent(now,now,0,key,0));super.dispatchKeyEvent(new KeyEvent(now,now,1,key,0));}
    @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BUTTON_A){if(e.getAction()==0&&e.getRepeatCount()==0){held=true;armed=getCurrentFocus();}if(e.getAction()==1){View target=armed;boolean click=held&&!e.isCanceled()&&target!=null&&target==getCurrentFocus();held=false;armed=null;if(click&&target.isEnabled())target.performClick();}return true;}if(e.getKeyCode()==KeyEvent.KEYCODE_BUTTON_B){if(e.getAction()==1&&!e.isCanceled())finish();return true;}return super.dispatchKeyEvent(e);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_MOVE||!e.isFromSource(InputDevice.SOURCE_JOYSTICK))return super.dispatchGenericMotionEvent(e);float x=e.getAxisValue(0),y=e.getAxisValue(1),m=Math.max(Math.abs(x),Math.abs(y));int next=direction;if(m<.35f)next=0;else if(m>=.55f){boolean horizontal=direction==21||direction==22?Math.abs(y)<=Math.abs(x)+.12f:direction==19||direction==20?Math.abs(x)>Math.abs(y)+.12f:Math.abs(x)>Math.abs(y);next=horizontal?(x<0?21:22):(y<0?19:20);}if(next!=direction){direction=next;if(next!=0){move(next);nextMove=SystemClock.uptimeMillis()+360;}}return true;}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);ui.removeCallbacks(tick);if(focus)ui.post(tick);else{held=false;armed=null;direction=0;}}
    @Override protected void onPause(){ui.removeCallbacks(tick);held=false;armed=null;direction=0;super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);network.shutdownNow();super.onDestroy();}
}
