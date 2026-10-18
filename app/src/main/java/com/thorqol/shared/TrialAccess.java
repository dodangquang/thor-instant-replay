package com.thorqol.shared;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/** Server-owned per-device trials; missing config/network never creates local trial time or slots. */
public final class TrialAccess {
    private static final String PROCESS=UUID.randomUUID().toString();
    private TrialAccess() {}
    public static boolean enabled(Context c){String p=c.getPackageName();return p.equals("vn.vhbridge.trial")||p.equals("com.khogame.thor.trial")||p.equals("com.thor.instantreplay.trial");}
    static String app(Context c){String p=c.getPackageName();if(!enabled(c))throw new SecurityException("Not a trial package");return p.substring(0,p.length()-6);}
    static boolean english(Context c){return "en".equals(c.getSharedPreferences("thorqol_ui",0).getString("language","vi"));}
    static String t(Context c,String vi,String en){return english(c)?en:vi;}
    public static String label(Context c){String p=app(c);return p.equals("vn.vhbridge")?"Việt Hóa PC":p.equals("com.khogame.thor")?"Emu Game Store":"Android Replay";}
    public static String gameKey(String scope,String id) throws Exception {
        if(scope==null||id==null||id.trim().isEmpty()||id.length()>4096)throw new SecurityException("Missing game identity");
        return hash(scope+"|"+Normalizer.normalize(id,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," "));
    }
    static String hash(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
    static String device(Context c)throws Exception{
        String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null||!id.matches("[0-9a-fA-F]{16}")||id.equals("9774d56d682e549c")||id.equals("0000000000000000"))throw new SecurityException("Stable Android identity unavailable");
        return hash("thorqol-trial-v1|"+app(c)+"|"+id.toLowerCase(Locale.ROOT));
    }
    static int boot(Context c){return Settings.Global.getInt(c.getContentResolver(),"boot_count",-1);}
    static final class Config {
        final String api,kid,spki,shop;
        Config(Context c)throws Exception{
            JSONObject j=new JSONObject(read(c.getAssets().open("thorqol-trial.json"),16384));
            api=j.getString("apiBase");kid=j.getString("keyId");spki=j.getString("publicKeySpki");shop=j.getString("shopUrl");
            if(!j.getBoolean("enabled")||!"https://thorqol-license.netlify.app".equals(api)||kid.isEmpty()||spki.isEmpty()||!LicenseConfig.allowedShop(shop))throw new SecurityException("Invalid trial configuration");
        }
    }
    static String read(InputStream input,int maximum)throws Exception{
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>maximum)throw new IOException("Response too large");out.write(buffer,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}finally{input.close();}
    }
    static JSONObject state(Context c)throws Exception{return new JSONObject(SecureStore.get(c,"trial_state"));}
    static JSONObject verified(Context c)throws Exception{
        Config cfg=new Config(c);JSONObject saved=state(c);
        JSONObject grant=TrialGrant.verify(saved.getString("license"),cfg.spki,cfg.kid,app(c),device(c));
        long elapsed=SystemClock.elapsedRealtime(),base=saved.getLong("elapsed");int currentBoot=boot(c),oldBoot=saved.getInt("boot");
        long server=saved.getLong("serverTime");
        long now=TrialClock.now(server,grant.getLong("iat"),grant.getLong("exp"),base,elapsed,oldBoot,currentBoot,saved.optString("process"),PROCESS);
        grant.put("remaining_seconds",grant.getLong("exp")-now);return grant;
    }
    public static boolean canUse(Context c){try{if(!enabled(c))return false;if("TRIAL_EXPIRED".equals(SecureStore.get(c,"trial_denied")))return false;verified(c);return true;}catch(Exception error){return false;}}
    public static boolean guard(Activity a){if(canUse(a))return true;a.startActivity(new Intent(a,TrialActivity.class));a.finish();return false;}
    public static void open(Activity a){a.startActivity(new Intent(a,TrialActivity.class));}
    public static String statusText(Context c){
        if("TRIAL_EXPIRED".equals(SecureStore.get(c,"trial_denied")))return error(c,"TRIAL_EXPIRED");
        try{JSONObject p=verified(c);if("com.thor.instantreplay".equals(app(c))){long minutes=(p.getLong("remaining_seconds")+59)/60;return t(c,"DÙNG THỬ · còn ","TRIAL · ")+(minutes/60)+t(c," giờ ","h ")+(minutes%60)+t(c," phút","m remaining");}
            int used=p.getInt("downloads_used");return t(c,"DÙNG THỬ · đã tải ","TRIAL · ")+used+"/3"+t(c," game · mỗi máy"," games · this device");
        }catch(Exception unavailable){if("com.thor.instantreplay".equals(app(c))&&"Trial lease expired".equals(unavailable.getMessage()))return error(c,"TRIAL_EXPIRED");return t(c,"DÙNG THỬ · cần kiểm tra qua mạng","TRIAL · online verification required");}
    }
    public static void start(Context c)throws Exception{request(c,"start",null);}
    public static void status(Context c)throws Exception{request(c,"status",null);}
    public static void claimDownload(Context c,String gameKey)throws Exception{
        if(!enabled(c))return;
        if(gameKey==null||!gameKey.matches("[a-f0-9]{64}"))throw new SecurityException("Missing trial game identity");
        request(c,"claim",gameKey);
    }
    private static synchronized void request(Context c,String action,String gameKey)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Trial network request must run off the UI thread");
        long requestedElapsed=SystemClock.elapsedRealtime();
        Config cfg=new Config(c);String identity=device(c);JSONObject body=new JSONObject().put("appId",app(c)).put("deviceHash",identity);if(gameKey!=null)body.put("gameKey",gameKey);
        HttpURLConnection conn=(HttpURLConnection)new URL(cfg.api+"/v1/trial/"+action).openConnection();
        conn.setRequestMethod("POST");conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(20000);conn.setReadTimeout(20000);conn.setUseCaches(false);conn.setDoOutput(true);
        conn.setRequestProperty("Accept","application/json");conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setRequestProperty("Cache-Control","no-store");
        try{
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);conn.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            int status=conn.getResponseCode();if(status>=300&&status<400)throw new SecurityException("Trial redirect refused");
            InputStream input=status<400?conn.getInputStream():conn.getErrorStream();if(input==null)throw new IOException("Empty trial response");JSONObject response=new JSONObject(read(input,32768));
            if(status<200||status>=300){JSONObject err=response.optJSONObject("error");String code=err==null?"SERVICE_UNAVAILABLE":err.optString("code");if("TRIAL_EXPIRED".equals(code))SecureStore.put(c,"trial_denied",code);throw new TrialException(code,error(c,code));}
            String license=response.getString("license");JSONObject grant=TrialGrant.verify(license,cfg.spki,cfg.kid,app(c),identity);long server=response.getLong("serverTime");
            if(server!=grant.getLong("iat"))throw new SecurityException("Unsigned time mismatch");
            JSONObject saved=new JSONObject().put("license",license).put("serverTime",server).put("elapsed",requestedElapsed).put("boot",boot(c)).put("process",PROCESS);
            SecureStore.put(c,"trial_state",saved.toString());SecureStore.remove(c,"trial_denied");
        }catch(TrialException denied){throw denied;}
        catch(Exception unavailable){throw new TrialException("SERVICE_UNAVAILABLE",t(c,"Cần kết nối mạng để kiểm tra dùng thử. Chưa bắt đầu tải game.","Connect to the internet to verify this trial. The game download has not started."));}
        finally{conn.disconnect();}
    }
    static String error(Context c,String code){
        if("TRIAL_DOWNLOAD_LIMIT".equals(code))return t(c,"Máy này đã dùng đủ 3 game thử. Bạn vẫn có thể tải lại 3 game đã chọn hoặc mở bản chính.","This device has used all 3 trial games. You can download those games again or get the full version.");
        if("TRIAL_EXPIRED".equals(code))return t(c,"24 giờ dùng thử Android Replay trên máy này đã kết thúc.","The 24-hour Android Replay trial on this device has ended.");
        if("TRIAL_NOT_STARTED".equals(code))return t(c,"Mở Tài khoản / Dùng thử và chọn Bắt đầu dùng thử trước khi tải.","Open Account / Trial and choose Start trial before downloading.");
        if("RATE_LIMITED".equals(code))return t(c,"Đã gửi nhiều yêu cầu. Vui lòng thử lại sau một phút.","Too many requests. Try again in one minute.");
        return t(c,"Dịch vụ dùng thử chưa sẵn sàng. Hãy thử lại sau.","The trial service is unavailable. Please try again later.");
    }
    public static final class TrialException extends Exception{public final String code;TrialException(String code,String text){super(text);this.code=code;}}
}
