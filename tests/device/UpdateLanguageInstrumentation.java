package com.thorqol.qa;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Signed companion tests. Never starts/resets a trial, logs in or changes device permissions. */
public final class UpdateLanguageInstrumentation extends Instrumentation {
    private Bundle args;private Activity activity;private int passed;private Context target;
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
    private Class<?> type(String name)throws Exception{return target.getClassLoader().loadClass("com.thorqol.shared."+name);}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;}
    private Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private Object call(Object value,String name,Class<?>[] signature,Object... arguments)throws Exception{Method method=value.getClass().getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(value,arguments);}
    private Activity launch(){return startActivitySync(new Intent().setClassName(target.getPackageName(),"com.thorqol.shared.AppUpdateActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));}
    private int state()throws Exception{AtomicInteger out=new AtomicInteger();runOnMainSync(()->{try{out.set((Integer)field(field(activity,"updates"),"state"));}catch(Exception e){throw new RuntimeException(e);}});return out.get();}
    private void waitState(int expected,long limit)throws Exception{long end=SystemClock.elapsedRealtime()+limit;while(SystemClock.elapsedRealtime()<end){int value=state();if(value==expected)return;if(value==6)throw new AssertionError("Updater error: "+field(field(activity,"updates"),"message"));SystemClock.sleep(75);}throw new AssertionError("Timed out waiting for updater state "+expected+", got "+state());}
    private String hash(byte[] value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private String preferencesHash()throws Exception{TreeMap<String,String> rows=new TreeMap<>();for(Map.Entry<String,?> row:target.getSharedPreferences("thorqol_secrets",0).getAll().entrySet())rows.put(row.getKey(),String.valueOf(row.getValue()));return hash(rows.toString().getBytes(StandardCharsets.UTF_8));}
    private void screenshot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();if(b==null)return;File dir=new File(target.getExternalFilesDir(null),"thorqol-qa");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    private KeyEvent key(int action,int key,int repeat){long now=SystemClock.uptimeMillis();return new KeyEvent(now,now,action,key,repeat);}
    private void stick(Dialog dialog,float x)throws Exception{runOnMainSync(()->{long now=SystemClock.uptimeMillis();MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.setAxisValue(MotionEvent.AXIS_X,x);MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_JOYSTICK,0);dialog.onGenericMotionEvent(event);event.recycle();});}
    private void popup()throws Exception{
        final String scope="qa_language_"+System.nanoTime();Context isolated=new ContextWrapper(target){@Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(scope+name,mode);}};
        Class<?> prompt=type("LanguagePrompt");check((Boolean)prompt.getMethod("needed",Context.class).invoke(null,isolated),"Fresh preferences request a language");
        prompt.getMethod("select",Context.class,String.class).invoke(null,isolated,"en");check(!(Boolean)prompt.getMethod("needed",Context.class).invoke(null,isolated),"Saved selection never prompts again");check("en".equals(isolated.getSharedPreferences("thorqol_ui",0).getString("language",null)),"English persists to the shared account UI");
        target.deleteSharedPreferences(scope+"thorqol_ui");target.deleteSharedPreferences(scope+"android_replay_ui");
        AtomicReference<Dialog> dialog=new AtomicReference<>();AtomicInteger selected=new AtomicInteger();
        runOnMainSync(()->{try{dialog.set((Dialog)prompt.getMethod("show",Activity.class,Runnable.class).invoke(null,activity,(Runnable)selected::incrementAndGet));}catch(Exception e){throw new RuntimeException(e);}});
        SystemClock.sleep(200);screenshot("first-language");
        runOnMainSync(()->dialog.get().dispatchKeyEvent(key(1,KeyEvent.KEYCODE_BUTTON_A,0)));check(selected.get()==0,"A release carried from another screen cannot choose a language");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitForIdleSync();
        AtomicReference<View> focus=new AtomicReference<>();runOnMainSync(()->focus.set(dialog.get().getCurrentFocus()));check(focus.get() instanceof Button&&((Button)focus.get()).getText().toString().startsWith("ENGLISH"),"D-pad reaches English");
        stick(dialog.get(),-.9f);runOnMainSync(()->focus.set(dialog.get().getCurrentFocus()));check(((Button)focus.get()).getText().toString().startsWith("TIẾNG VIỆT"),"Left stick moves focus to Vietnamese");
        stick(dialog.get(),0f);stick(dialog.get(),.9f);stick(dialog.get(),0f);runOnMainSync(()->focus.set(dialog.get().getCurrentFocus()));check(((Button)focus.get()).getText().toString().startsWith("ENGLISH"),"Left stick returns to English and stops at neutral");
        runOnMainSync(()->{dialog.get().dispatchKeyEvent(key(0,KeyEvent.KEYCODE_BUTTON_A,0));for(int i=1;i<20;i++)dialog.get().dispatchKeyEvent(key(0,KeyEvent.KEYCODE_BUTTON_A,i));});check(selected.get()==0,"Holding or repeating A never chooses early");
        runOnMainSync(()->dialog.get().dispatchKeyEvent(key(1,KeyEvent.KEYCODE_BUTTON_A,0)));check(selected.get()==1,"A release chooses exactly once");check("en".equals(target.getSharedPreferences("thorqol_ui",0).getString("language",null)),"Selected language is stored");
        if(target.getPackageName().startsWith("com.thor.instantreplay"))check("en".equals(target.getSharedPreferences("android_replay_ui",0).getString("language",null)),"Replay native resources use the same English preference");
    }
    private void rejected(Object updates,File file,Object spec,String why)throws Exception{try{call(updates,"verify",new Class<?>[]{File.class,type("UpdateSpec")},file,spec);}catch(InvocationTargetException expected){check(expected.getCause() instanceof Exception,why);return;}throw new AssertionError(why);}
    @Override public void onStart(){Bundle result=new Bundle();target=getTargetContext();SharedPreferences shared=target.getSharedPreferences("thorqol_ui",0),replay=target.getSharedPreferences("android_replay_ui",0);Map<String,?> oldShared=shared.getAll(),oldReplay=replay.getAll();
        try{
            String before=preferencesHash();activity=launch();
            if("after".equals(args.getString("mode"))){waitState(2,45000);check(args.getString("version").equals(target.getPackageManager().getPackageInfo(target.getPackageName(),0).versionName),"Official signed APK installed over the older app");
                File baseline=new File(target.getExternalFilesDir(null),"thorqol-qa-before.txt");String saved=new String(java.nio.file.Files.readAllBytes(baseline.toPath()),StandardCharsets.UTF_8);check(saved.equals(preferencesHash()),"Encrypted identity, sessions and Trial state survived the upgrade");baseline.delete();screenshot("updated-current");
            }else{
                java.nio.file.Files.write(new File(target.getExternalFilesDir(null),"thorqol-qa-before.txt").toPath(),before.getBytes(StandardCharsets.UTF_8));
                popup();runOnMainSync(()->activity.finish());activity=launch();waitState(3,45000);
                Object updates=field(activity,"updates"),spec=field(updates,"release");check(target.getPackageName().equals(field(spec,"packageName")),"Manifest matches the exact Trial package");check(args.getString("version").equals(field(spec,"versionName")),"New stable version is offered online");
                runOnMainSync(()->{try{((Button)field(activity,"download")).requestFocusFromTouch();activity.dispatchKeyEvent(key(1,KeyEvent.KEYCODE_BUTTON_A,0));}catch(Exception e){throw new RuntimeException(e);}});check(state()==3,"A release carried into the update screen cannot auto-download");
                runOnMainSync(()->{activity.dispatchKeyEvent(key(0,KeyEvent.KEYCODE_BUTTON_A,0));for(int i=1;i<10;i++)activity.dispatchKeyEvent(key(0,KeyEvent.KEYCODE_BUTTON_A,i));});check(state()==3,"Holding A does not start the download");
                runOnMainSync(()->activity.dispatchKeyEvent(key(1,KeyEvent.KEYCODE_BUTTON_A,0)));waitState(5,180000);check(state()==5,"Network download passed size, SHA-256, package, version and certificate checks");screenshot("update-ready");
                File apk=new File(target.getFilesDir(),"thorqol-update/update.apk");check(apk.length()==(Long)field(spec,"bytes"),"Verified file has the declared size");
                Uri uri=Uri.parse("content://"+target.getPackageName()+".thorqol.updates/update.apk");check("application/vnd.android.package-archive".equals(target.getContentResolver().getType(uri)),"Only APK MIME is served");try(InputStream in=target.getContentResolver().openInputStream(uri)){check(in.read()>=0,"Verified private APK can be read through the installer provider");}
                try{target.getContentResolver().openInputStream(Uri.parse(uri+"?outside=1"));throw new AssertionError("Unexpected URI accepted");}catch(FileNotFoundException|IllegalArgumentException expected){check(true,"Provider rejects unexpected URI parameters");}
                File tampered=new File(target.getCacheDir(),"qa-update-tampered.apk");java.nio.file.Files.copy(apk.toPath(),tampered.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);try(RandomAccessFile file=new RandomAccessFile(tampered,"rw")){file.seek(16);file.write(0x11);}rejected(updates,tampered,spec,"Modified APK bytes are rejected");tampered.delete();
                File wrong=new File(target.getCacheDir(),"qa-update-wrong-signer.apk");try(InputStream in=getContext().getAssets().open("wrong-signer.apk");OutputStream out=new FileOutputStream(wrong)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                String wrongHash=hash(java.nio.file.Files.readAllBytes(wrong.toPath()));Object wrongSpec=type("UpdateSpec").getConstructor(String.class,String.class,long.class,String.class,int.class,long.class,String.class,String.class,String.class).newInstance(target.getPackageName(),target.getPackageName(),field(spec,"versionCode"),field(spec,"versionName"),field(spec,"minSdk"),wrong.length(),wrongHash,field(spec,"url"),"QA wrong signer only");rejected(updates,wrong,wrongSpec,"A matching manifest cannot authorize another signing key");wrong.delete();
                AtomicReference<Intent> intent=new AtomicReference<>();ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent next){intent.set(next);return new ActivityResult(Activity.RESULT_CANCELED,null);}};addMonitor(monitor);
                try{runOnMainSync(()->{try{call(updates,"install",new Class<?>[]{Activity.class},activity);}catch(Exception e){throw new RuntimeException(e);}});long end=SystemClock.elapsedRealtime()+15000;while(intent.get()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);check(intent.get()!=null,"Installer or source-permission screen is requested only after a verified APK");
                    if(Intent.ACTION_VIEW.equals(intent.get().getAction()))check(uri.equals(intent.get().getData())&&(intent.get().getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"Installer receives an exact temporary read grant");else check(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(intent.get().getAction()),"Android source permission is requested without silently changing it");
                }finally{removeMonitor(monitor);}
                check(before.equals(preferencesHash()),"Updates and language selection do not change encrypted account or Trial state");
            }
            result.putString("stream","THORQOL UPDATE LANGUAGE PASS: "+passed+" checks; "+target.getPackageName()+"; "+args.getString("mode","before")+"\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));result.putString("stream","THORQOL UPDATE LANGUAGE FAILED\n"+trace);finish(Activity.RESULT_CANCELED,result);}
        finally{
            restore(shared,oldShared,"language","language_onboarding_done");restore(replay,oldReplay,"language");if(activity!=null)runOnMainSync(()->activity.finish());
        }
    }
    private void restore(SharedPreferences p,Map<String,?> old,String... keys){SharedPreferences.Editor editor=p.edit();for(String key:keys){Object value=old.get(key);if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else editor.remove(key);}editor.commit();}
}
