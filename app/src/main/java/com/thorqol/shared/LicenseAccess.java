package com.thorqol.shared;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Enforcement stays off in preview builds and fails closed if enabled with incomplete configuration. */
public final class LicenseAccess {
    private static final java.util.concurrent.ExecutorService NETWORK=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"thorqol-license-refresh");t.setDaemon(true);return t;});
    private static long lastAttempt;private static boolean refreshing;
    private LicenseAccess(){}
    static JSONObject account(Context c){try{return new JSONObject(SecureStore.get(c,"account"));}catch(Exception e){return new JSONObject();}}
    static JSONObject verified(Context c) throws Exception {
        LicenseConfig config=new LicenseConfig(c);if(!config.ready())throw new SecurityException("Not configured");
        long now=System.currentTimeMillis()/1000,last=0;try{last=Long.parseLong(SecureStore.get(c,"last_seen"));}catch(Exception ignored){}
        JSONObject payload=LicenseGrant.verify(SecureStore.get(c,"grant"),config.spki,config.keyId,DeviceIdentity.get(c),c.getPackageName(),account(c).optString("id"),now,last);
        SecureStore.put(c,"last_seen",Long.toString(Math.max(now,last)));return payload;
    }
    public static boolean canUse(Context c){try{LicenseConfig config=new LicenseConfig(c);if(!config.enforce)return true;verified(c);return true;}catch(Exception e){return false;}}
    public static boolean guard(Activity a){
        if(!canUse(a)){a.startActivity(new Intent(a,AccountActivity.class).putExtra("return_to_app",true));a.finish();return false;}
        java.lang.ref.WeakReference<Activity> activity=new java.lang.ref.WeakReference<>(a);
        refresh(a,()->{Activity current=activity.get();if(current!=null&&!current.isFinishing()&&!current.isDestroyed())guard(current);});return true;
    }
    public static void open(Activity a){a.startActivity(new Intent(a,AccountActivity.class));}
    static void clearSession(Context c){for(String key:new String[]{"token","account","grant","device"})SecureStore.remove(c,key);}
    /** Check revocation online at launch/resume; transient failures retain only a still-valid signed lease. */
    public static synchronized void refresh(Context context,Runnable revoked){
        Context c=context.getApplicationContext();long now=android.os.SystemClock.elapsedRealtime();
        if(refreshing||(lastAttempt!=0&&now-lastAttempt<120000)||SecureStore.get(c,"token").isEmpty())return;
        LicenseConfig config;try{config=new LicenseConfig(c);if(!config.ready())return;}catch(Exception e){return;}
        refreshing=true;lastAttempt=now;
        NETWORK.execute(()->{
            boolean denied=false;
            try{
                JSONObject request=new JSONObject().put("deviceKey",DeviceIdentity.get(c)).put("appId",c.getPackageName());
                JSONObject result=AccountActivity.request(c,config,"POST","/v1/license/refresh",request,true);
                String grant=result.getString("license");long seconds=System.currentTimeMillis()/1000;
                LicenseGrant.verify(grant,config.spki,config.keyId,DeviceIdentity.get(c),c.getPackageName(),account(c).getString("id"),seconds,0);
                SecureStore.put(c,"grant",grant);SecureStore.put(c,"device",result.getJSONObject("device").getString("id"));SecureStore.put(c,"last_seen",Long.toString(seconds));
            }catch(AccountActivity.ApiError e){
                if(java.util.Arrays.asList("LOGIN_REQUIRED","DEVICE_REVOKED","PURCHASE_REQUIRED","SESSION_DEVICE_MISMATCH").contains(e.code)){
                    SecureStore.remove(c,"grant");denied=true;
                    if("LOGIN_REQUIRED".equals(e.code)||"SESSION_DEVICE_MISMATCH".equals(e.code))clearSession(c);
                }
            }catch(Exception transientFailure){/* Offline failures cannot extend an expired grant. */}
            finally{synchronized(LicenseAccess.class){refreshing=false;}}
            if(denied&&config.enforce&&revoked!=null)new android.os.Handler(android.os.Looper.getMainLooper()).post(revoked);
        });
    }
}
