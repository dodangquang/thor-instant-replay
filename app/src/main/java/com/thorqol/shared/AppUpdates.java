package com.thorqol.shared;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Same-package signed APK updates. Never downloads games or changes account/trial state. */
public final class AppUpdates {
    public static final int IDLE=0,CHECKING=1,CURRENT=2,AVAILABLE=3,DOWNLOADING=4,READY=5,ERROR=6,VERIFYING=7;
    private static final ExecutorService AUTO=Executors.newSingleThreadExecutor();private static boolean autoBusy;private static long attempted;
    private final Context context;private final Runnable changed;private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile boolean closed,cancelled;private volatile HttpURLConnection active;
    public int state=IDLE;public String message="",version="",notes="";public long done,total;
    private UpdateSpec release;private JSONObject metadata;
    public AppUpdates(Context context,Runnable changed){this.context=context.getApplicationContext();this.changed=changed;}
    String t(String vi,String en){return "en".equals(LanguagePrompt.language(context))?en:vi;}
    static int flags(){return Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;}
    static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    static PackageInfo installed(Context c)throws Exception{return c.getPackageManager().getPackageInfo(c.getPackageName(),flags());}
    public String installedVersion(){try{return installed(context).versionName;}catch(Exception e){return "?";}}
    private File directory(){File f=new File(context.getFilesDir(),"thorqol-update");f.mkdirs();return f;}
    private File apk(){return new File(directory(),"update.apk");}
    private void post(Runnable action){ui.post(()->{if(!closed){action.run();changed.run();}});}
    public boolean busy(){return state==CHECKING||state==DOWNLOADING||state==VERIFYING;}
    public static void open(Activity a){a.startActivity(new Intent(a,AppUpdateActivity.class));}
    public static boolean hasUpdate(Context c){try{return c.getSharedPreferences("thorqol_updates",0).getLong("offer_code",0)>code(installed(c));}catch(Exception e){return false;}}
    public static synchronized void autoCheck(Activity a){
        if(autoBusy||a.isFinishing()||a.isDestroyed())return;long now=SystemClock.elapsedRealtime();if(attempted!=0&&now-attempted<120000)return;
        Context c=a.getApplicationContext();long age=System.currentTimeMillis()-c.getSharedPreferences("thorqol_updates",0).getLong("checked_at",0);if(age>=0&&age<6*3600000L)return;
        autoBusy=true;attempted=now;java.lang.ref.WeakReference<Activity> ref=new java.lang.ref.WeakReference<>(a);
        AUTO.execute(()->{try{JSONObject json=readManifest(c);UpdateSpec spec=parse(c,json);if(spec.minSdk>Build.VERSION.SDK_INT)return;
            c.getSharedPreferences("thorqol_updates",0).edit().putLong("checked_at",System.currentTimeMillis()).putLong("offer_code",spec.versionCode).apply();
            if(spec.versionCode<=code(installed(c)))return;
            new Handler(Looper.getMainLooper()).post(()->{Activity current=ref.get();if(current==null||current.isFinishing()||current.isDestroyed()||!current.hasWindowFocus())return;
                android.content.SharedPreferences p=c.getSharedPreferences("thorqol_updates",0);if(p.getLong("notified_code",0)==spec.versionCode)return;p.edit().putLong("notified_code",spec.versionCode).apply();
                boolean en="en".equals(LanguagePrompt.language(c));new AlertDialog.Builder(current).setTitle(en?"App update available":"Có bản cập nhật ứng dụng").setMessage(spec.versionName+"\n\n"+(en?"Download and verify the signed APK before installing over this app.":"Tải và kiểm tra chữ ký APK trước khi cài đè ứng dụng này.")).setNegativeButton(en?"Later":"Để sau",null).setPositiveButton(en?"View update":"Xem cập nhật",(d,w)->open(current)).show();});
        }catch(Exception ignored){/* Offline update failure never changes access or stored app data. */}finally{synchronized(AppUpdates.class){autoBusy=false;}}});
    }
    static UpdateSpec parse(Context c,JSONObject json)throws Exception{
        if(json.getInt("schemaVersion")!=1||!json.getString("channel").equals(c.getPackageName().endsWith(".trial")?"trial":"full"))throw new IOException("Wrong update channel");
        return new UpdateSpec(c.getPackageName(),json.getString("packageName"),json.getLong("versionCode"),json.getString("versionName"),json.getInt("minSdk"),json.getLong("bytes"),json.getString("sha256"),json.getString("apkUrl"),json.optString("en".equals(LanguagePrompt.language(c))?"notesEn":"notesVi",""));
    }
    static JSONObject readManifest(Context c)throws Exception{HttpURLConnection conn=connect(new URL(UpdateSpec.manifest(c.getPackageName())),false);try{
        if(conn.getContentLengthLong()>65536)throw new IOException("Update metadata too large");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Update metadata too large");out.write(b,0,n);}}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
    }finally{conn.disconnect();}}
    static HttpURLConnection connect(URL url,boolean redirects)throws Exception{
        for(int count=0;count<5;count++){
            String host=url.getHost();if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getRef()!=null||url.getPort()!=-1||!(redirects?(host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com")):host.equals("thorqol-license.netlify.app")))throw new IOException("Untrusted update redirect");
            HttpURLConnection conn=(HttpURLConnection)url.openConnection();conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(15000);conn.setReadTimeout(20000);conn.setUseCaches(false);conn.setRequestProperty("Accept",redirects?"application/octet-stream":"application/json");conn.setRequestProperty("User-Agent","ThorQOL-Updater/1");conn.setRequestProperty("Cache-Control","no-cache");
            int status=conn.getResponseCode();if(status>=300&&status<400){String location=conn.getHeaderField("Location");conn.disconnect();if(!redirects||location==null)throw new IOException("Update redirect refused");url=new URL(url,location);continue;}
            if(status!=200){conn.disconnect();throw new IOException("Update server HTTP "+status);}return conn;
        }throw new IOException("Too many update redirects");
    }
    public void check(){if(busy()||closed)return;state=CHECKING;message=t("Đang kiểm tra bản mới…","Checking for updates…");changed.run();io.execute(()->{try{
        JSONObject json=readManifest(context);UpdateSpec candidate=parse(context,json);long current=code(installed(context));
        if(candidate.versionCode>current&&candidate.minSdk>Build.VERSION.SDK_INT)throw new IOException(t("Bản mới cần Android API ","The update requires Android API ")+candidate.minSdk);
        boolean cached=false;if(candidate.versionCode>current&&apk().isFile())try{verify(apk(),candidate);cached=true;}catch(Exception ignored){}
        final boolean ready=cached;post(()->{metadata=json;release=candidate;version=candidate.versionName;notes=candidate.notes;total=candidate.bytes;done=ready?total:0;state=candidate.versionCode<=current?CURRENT:ready?READY:AVAILABLE;message=state==CURRENT?t("Bạn đang dùng bản mới nhất.","You are using the latest version."):ready?t("APK đã được kiểm tra. Sẵn sàng cập nhật.","APK verified. Ready to update."):t("Có bản mới. Chỉ tải khi bạn chọn cập nhật.","An update is available. Download starts only when you choose.");context.getSharedPreferences("thorqol_updates",0).edit().putLong("checked_at",System.currentTimeMillis()).putLong("offer_code",candidate.versionCode).apply();});
    }catch(Exception e){post(()->{state=ERROR;message=t("Chưa kiểm tra được cập nhật. Kiểm tra mạng và thử lại.","Could not check for updates. Check your connection and retry.");});}});}
    public void download(){if(state!=AVAILABLE||release==null||closed)return;UpdateSpec candidate=release;JSONObject json=metadata;state=DOWNLOADING;cancelled=false;done=0;total=candidate.bytes;message=t("Đang tải APK… Giữ màn hình cập nhật mở.","Downloading APK… Keep the update screen open.");changed.run();
        io.execute(()->{File part=new File(directory(),"update.part");try{HttpURLConnection conn=connect(new URL(candidate.url),true);active=conn;long received=0,last=0;
            try{if(conn.getContentLengthLong()>candidate.bytes)throw new IOException("APK exceeds declared size");try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(part)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(cancelled||closed)throw new IOException("Cancelled");received+=n;if(received>candidate.bytes)throw new IOException("APK exceeds declared size");out.write(b,0,n);long now=SystemClock.elapsedRealtime();if(now-last>=100||received==candidate.bytes){last=now;final long progress=received;post(()->done=progress);}}}}finally{active=null;conn.disconnect();}
            if(received!=candidate.bytes||cancelled||closed)throw new IOException("Incomplete APK");post(()->{state=VERIFYING;message=t("Đang kiểm tra SHA-256 và chữ ký APK…","Verifying APK SHA-256 and signing certificate…");});verify(part,candidate);if(cancelled||closed)throw new IOException("Cancelled");
            Files.move(part.toPath(),apk().toPath(),StandardCopyOption.REPLACE_EXISTING);try(FileOutputStream out=new FileOutputStream(new File(directory(),"release.json"))){out.write(json.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            post(()->{state=READY;done=total;message=t("Đã kiểm tra đúng ứng dụng, phiên bản, SHA-256 và chữ ký.","App identity, version, SHA-256 and signing certificate verified.");});
        }catch(Exception e){post(()->{state=cancelled?AVAILABLE:ERROR;message=cancelled?t("Đã hủy tải cập nhật.","Update download cancelled."):t("Không tải hoặc xác minh được APK. Hãy thử lại.","Could not download or verify the APK. Please retry.");});}finally{part.delete();}});
    }
    static String digest(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}return hex(hash.digest());}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static Set<String> signatures(PackageInfo info)throws Exception{Signature[] values=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;if(values==null||values.length==0)throw new IOException("Unsigned APK");Set<String> result=new HashSet<>();for(Signature value:values)result.add(hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())));return result;}
    void verify(File file,UpdateSpec spec)throws Exception{
        if(!context.getPackageName().equals(spec.packageName)||file.length()!=spec.bytes||!digest(file).equals(spec.sha256))throw new IOException("APK digest mismatch");
        PackageInfo found=context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),flags()),current=installed(context);
        if(found==null||!context.getPackageName().equals(found.packageName)||code(found)!=spec.versionCode||code(found)<=code(current)||!spec.versionName.equals(found.versionName)||found.applicationInfo==null||found.applicationInfo.minSdkVersion!=spec.minSdk||spec.minSdk>Build.VERSION.SDK_INT||!signatures(found).equals(signatures(current)))throw new IOException("Wrong app, version or signing certificate");
    }
    public void install(Activity a){if(state!=READY||release==null||closed)return;UpdateSpec candidate=release;state=VERIFYING;message=t("Kiểm tra lại APK trước khi cài…","Rechecking APK before installation…");changed.run();io.execute(()->{try{verify(apk(),candidate);post(()->{state=READY;if(a.isFinishing()||a.isDestroyed())return;try{
                if(!context.getPackageManager().canRequestPackageInstalls()){a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName())));message=t("Cho phép cài ứng dụng từ nguồn này, quay lại và bấm Cài cập nhật.","Allow installs from this source, return and select Install update.");return;}
                Uri uri=Uri.parse("content://"+context.getPackageName()+".thorqol.updates/update.apk");Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("ThorQOL signed update",uri));a.startActivity(intent);message=t("Xác nhận Cập nhật trong trình cài đặt Android.","Confirm Update in the Android installer.");
            }catch(Exception e){state=ERROR;message=t("Chưa mở được trình cài đặt Android.","Could not open the Android installer.");}});
        }catch(Exception e){post(()->{state=ERROR;message=t("APK không còn hợp lệ. Hãy tải lại.","The saved APK is no longer valid. Download it again.");});}});}
    public void cancel(){cancelled=true;HttpURLConnection conn=active;if(conn!=null)conn.disconnect();}
    public void close(){closed=true;cancel();io.shutdownNow();ui.removeCallbacksAndMessages(null);}
}
