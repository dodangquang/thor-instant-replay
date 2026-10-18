package com.thor.instantreplay;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Real-window geometry and first-use guide regression; never activates capture or changes permissions. */
final class FocusSetupRegression {
    private final ReplayUiInstrumentation test;
    private final Context ctx;
    private Activity screen;
    private int checks;
    FocusSetupRegression(ReplayUiInstrumentation test){this.test=test;ctx=test.getTargetContext();}
    private void check(boolean condition,String description){if(!condition)throw new AssertionError(description);checks++;System.out.println("PASS "+description);}
    private int id(String name){return ctx.getResources().getIdentifier(name,"id",ctx.getPackageName());}
    private void ui(Runnable work){test.runOnMainSync(work);}
    private Dialog dialog()throws Exception{
        AtomicReference<Dialog> value=new AtomicReference<>();
        ui(()->{try{Field field=screen.getClass().getDeclaredField("setupDialog");field.setAccessible(true);value.set((Dialog)field.get(screen));}catch(Exception error){throw new RuntimeException(error);}});
        return value.get();
    }
    private Dialog awaitGuide()throws Exception{
        long end=SystemClock.uptimeMillis()+6000;
        while(SystemClock.uptimeMillis()<end){Dialog guide=dialog();if(guide!=null&&guide.isShowing())return guide;SystemClock.sleep(60);}
        throw new AssertionError("First-use setup guide did not open in the actual focused window");
    }
    private void key(Dialog dialog,int code,int action,int repeat){long now=SystemClock.uptimeMillis();ui(()->dialog.dispatchKeyEvent(new KeyEvent(now,now,action,code,repeat)));}
    private void stick(Dialog dialog,float x,float y){
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.setAxisValue(MotionEvent.AXIS_X,x);coords.setAxisValue(MotionEvent.AXIS_Y,y);
        long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_JOYSTICK,0);
        try{dialog.dispatchGenericMotionEvent(event);}finally{event.recycle();}
    }
    private void text(View view,StringBuilder out){
        if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)text(group.getChildAt(i),out);}
    }
    private void screenshot(String name)throws Exception{
        SystemClock.sleep(220);Bitmap bitmap=test.getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("Screenshot unavailable");
        try(FileOutputStream out=new FileOutputStream(new File(ctx.getExternalFilesDir(null),"qa-280-"+name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private void checkGuide(String lang)throws Exception{
        Dialog guide=awaitGuide();
        check(guide.isShowing(),lang+" first-use guide opens automatically");
        ui(()->{
            StringBuilder content=new StringBuilder();text(guide.getWindow().getDecorView(),content);
            check(content.toString().contains(lang.equals("en")?"ADB · 6 SETUP STEPS":"ADB · 6 MỤC THIẾT LẬP"),lang+" actual guide title uses the selected language");
            String[] titles=lang.equals("en")?new String[]{"1 · GAME AUDIO AND MICROPHONE","2 · NOTIFICATIONS · ANDROID 13+","3 · BACKGROUND CAPTURE","4 · FLOATING CLIP BUTTON","5 · BATTERY OPTIMIZATION EXEMPTION","6 · HOME / L3+R3 AND THOR WAYFINDER"}
                :new String[]{"1 · ÂM THANH GAME VÀ MICRO","2 · THÔNG BÁO · ANDROID 13+","3 · QUAY NỀN","4 · NÚT NỔI CLIP","5 · KHÔNG TỐI ƯU PIN","6 · HOME / L3+R3 VÀ THOR WAYFINDER"};
            for(String title:titles)check(content.toString().contains(title),lang+" guide includes "+title);
            check(content.toString().contains(lang.equals("en")?"REPLACES the entire list":"THAY TOÀN BỘ danh sách"),lang+" explains replacement of the accessibility list");
            TextView command=guide.findViewById(id("setupCommand"));
            String[] expected={"adb devices","adb shell pm grant com.thor.instantreplay android.permission.RECORD_AUDIO","adb shell pm grant com.thor.instantreplay android.permission.POST_NOTIFICATIONS","adb shell appops set com.thor.instantreplay PROJECT_MEDIA allow","adb shell appops set com.thor.instantreplay SYSTEM_ALERT_WINDOW allow","adb shell dumpsys deviceidle whitelist +com.thor.instantreplay","adb shell settings put secure enabled_accessibility_services com.thor.instantreplay/.service.ThorKeyTriggerService:com.thorwayfinder.app/.ForegroundAppService","adb shell settings put secure accessibility_enabled 1"};
            ArrayList<String> actual=new ArrayList<>();for(String line:command.getText().toString().split("\n"))if(line.startsWith("adb "))actual.add(line);
            check(java.util.Arrays.asList(expected).equals(actual),lang+" displays all eight exact adb lines with Replay and Wayfinder components");
            guide.findViewById(id("btnSetupCopy")).performClick();
            ClipboardManager clipboard=(ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            check(clipboard.hasPrimaryClip()&&command.getText().toString().contentEquals(clipboard.getPrimaryClip().getItemAt(0).getText()),lang+" Copy All copies the complete command pack without executing it");
            View close=guide.findViewById(id("btnSetupClose"));check(close.requestFocusFromTouch(),lang+" guide Close receives controller focus");
            guide.findViewById(id("setupScroll")).requestFocusFromTouch();
        });
        screenshot("setup-"+lang+"-top");
        key(guide,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.ACTION_DOWN,0);key(guide,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.ACTION_UP,0);SystemClock.sleep(350);
        AtomicReference<Integer> offset=new AtomicReference<>();ui(()->offset.set(((ScrollView)guide.findViewById(id("setupScroll"))).getScrollY()));
        check(offset.get()>0,lang+" RB scrolls the instructions without activating an action");
        for(int page=0;page<24;page++){
            AtomicReference<Boolean> more=new AtomicReference<>();ui(()->more.set(guide.findViewById(id("setupScroll")).canScrollVertically(1)));if(!more.get())break;
            key(guide,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.ACTION_DOWN,0);key(guide,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.ACTION_UP,0);SystemClock.sleep(260);
        }
        ui(()->check(!guide.findViewById(id("setupScroll")).canScrollVertically(1),lang+" all six instruction sections and accessibility warning are reachable"));
        screenshot("setup-"+lang+"-scrolled");
        ui(()->guide.findViewById(id("setupCommandsScroll")).requestFocusFromTouch());
        for(int page=0;page<24;page++){
            AtomicReference<Boolean> more=new AtomicReference<>();ui(()->more.set(guide.findViewById(id("setupCommandsScroll")).canScrollVertically(1)));if(!more.get())break;
            key(guide,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.ACTION_DOWN,0);key(guide,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.ACTION_UP,0);SystemClock.sleep(260);
        }
        ui(()->check(!guide.findViewById(id("setupCommandsScroll")).canScrollVertically(1),lang+" command pane scrolls through the final two accessibility settings lines"));
        screenshot("setup-"+lang+"-commands-bottom");
        key(guide,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.ACTION_DOWN,0);key(guide,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.ACTION_UP,0);
        check(!guide.isShowing(),lang+" B closes only the setup guide");
        check(ctx.getSharedPreferences("android_replay_ui",0).getBoolean("setup_guide_seen_v1",false),lang+" first-use state is persisted separately from capture settings");
        ui(()->screen.findViewById(id("btnSetup")).performClick());Dialog reopened=awaitGuide();
        check(reopened.isShowing(),lang+" persistent SETUP button reopens the guide");
        ui(()->{
            View copy=reopened.findViewById(id("btnSetupCopy"));View accessibility=reopened.findViewById(id("btnSetupAccessibility"));
            check(copy.requestFocusFromTouch(),lang+" safe copy button receives focus for stick regression");
            stick(reopened,0,.56f);check(reopened.getCurrentFocus()==accessibility,lang+" stick makes one initial dialog move");
            for(int i=0;i<30;i++){stick(reopened,0,.54f);stick(reopened,0,.56f);}
            check(reopened.getCurrentFocus()==accessibility,lang+" .56/.54 stick jitter never bypasses the 330ms dialog hold delay");
            for(int i=0;i<30;i++){stick(reopened,.56f,.55f);stick(reopened,.55f,.56f);}
            check(reopened.getCurrentFocus()==accessibility,lang+" 45-degree diagonal jitter retains the chosen axis and initial hold delay");
            stick(reopened,0,-.7f);check(reopened.getCurrentFocus()==copy,lang+" deliberate direction reversal responds immediately");
            stick(reopened,0,0);
        });
        ui(()->reopened.findViewById(id("btnSetupClose")).requestFocusFromTouch());
        key(reopened,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.ACTION_DOWN,0);
        for(int repeat=1;repeat<=60;repeat++)key(reopened,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.ACTION_DOWN,repeat);
        check(reopened.isShowing(),lang+" A-down/60 repeats never activate the guide Close button");
        key(reopened,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.ACTION_UP,0);
        check(!reopened.isShowing(),lang+" one A release activates Close once");
    }
    private void visibleTargets(View view,ArrayList<View> result){
        if(!view.isShown()||!view.isEnabled())return;
        if(view.isFocusable()&&(!(view instanceof ViewGroup)||view.isClickable()))result.add(view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)visibleTargets(group.getChildAt(i),result);}
    }
    private void checkBounds(View view,String name){
        check(view.getScaleX()<=1.001f&&view.getScaleY()<=1.001f,name+" focus stays inside its control bounds");
        Matrix matrix=new Matrix();view.transformMatrixToGlobal(matrix);RectF drawn=new RectF(0,0,view.getWidth(),view.getHeight());matrix.mapRect(drawn);
        Rect visible=new Rect();boolean shown=view.getGlobalVisibleRect(visible);
        check(shown&&visible.left<=drawn.left+2&&visible.top<=drawn.top+2&&visible.right>=drawn.right-2&&visible.bottom>=drawn.bottom-2,
            name+" complete focused outline is visible through its parents: drawn="+drawn+" visible="+visible);
    }
    private void checkFocus(String lang)throws Exception{
        String[] tabs={"tabCapture","tabAudio","tabClips","tabControls"};
        for(int tab=0;tab<tabs.length;tab++){
            final String tabName=tabs[tab];ui(()->screen.findViewById(id(tabName)).performClick());test.waitForIdleSync();SystemClock.sleep(220);
            ArrayList<View> targets=new ArrayList<>();ui(()->visibleTargets(screen.findViewById(android.R.id.content),targets));
            check(!targets.isEmpty(),lang+" "+tabName+" exposes actual controller controls");
            for(int i=0;i<targets.size();i++){
                View target=targets.get(i);String label=target.getId()>0?ctx.getResources().getResourceEntryName(target.getId()):"clip-card-"+i;
                ui(()->{check(target.requestFocusFromTouch(),lang+" "+label+" receives native focus");target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true);});
                test.waitForIdleSync();SystemClock.sleep(160);
                ui(()->checkBounds(target,lang+" "+tabName+"/"+label));
                if(label.equals("tabControls")||label.equals("btnLanguage")||label.equals("swSoundChime")||label.equals("btnSetup"))screenshot("focus-"+lang+"-"+tabName+"-"+label);
            }
        }
    }
    String run(String expectedHash)throws Exception{
        Map<String,?> before=new HashMap<>(ctx.getSharedPreferences("thor_replay_prefs",0).getAll());
        if(expectedHash!=null)check(expectedHash.equals(ReplayUiInstrumentation.preferencesHash(before)),"Capture preferences match the pre-upgrade snapshot");
        String language=AppLanguage.get(ctx);SharedPreferences prefs=ctx.getSharedPreferences("android_replay_ui",0);
        boolean hadGuide=prefs.contains("setup_guide_seen_v1"), seen=prefs.getBoolean("setup_guide_seen_v1",false);
        try{
            for(String lang:new String[]{"vi","en"}){
                AppLanguage.set(ctx,lang);prefs.edit().remove("setup_guide_seen_v1").commit();
                screen=test.startActivitySync(new Intent().setClassName(ctx,"com.thor.instantreplay.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                test.waitForIdleSync();checkGuide(lang);checkFocus(lang);
                ui(screen::finish);test.waitForIdleSync();screen=null;
            }
            check(before.equals(ctx.getSharedPreferences("thor_replay_prefs",0).getAll()),"Every capture/audio preference remains unchanged after both language flows");
            return "PASS focus/setup: "+checks+" checks; VI/EN first-use/reopen/A-release, full control outlines, capture/audio preferences preserved";
        }finally{
            if(screen!=null){Dialog active=dialog();if(active!=null)ui(active::dismiss);ui(screen::finish);}
            AppLanguage.set(ctx,language);SharedPreferences.Editor edit=prefs.edit();if(hadGuide)edit.putBoolean("setup_guide_seen_v1",seen);else edit.remove("setup_guide_seen_v1");edit.commit();
        }
    }
}
