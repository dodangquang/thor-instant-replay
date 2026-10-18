package com.thorqol.shared;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native controller/touch account UI; no Ko-fi password, embedded browser login or test bypass. */
public final class AccountActivity extends Activity {
    static final int BG=0xff102f3b,PANEL=0xff211f30,LIME=0xffe4f43c,PINK=0xffff4795,WHITE=0xfff7ffff,MUTED=0xffb4ccd0;
    final ExecutorService worker=Executors.newSingleThreadExecutor();final Handler ui=new Handler(Looper.getMainLooper());
    LinearLayout body;TextView status;EditText email,code,name;LicenseConfig config;JSONArray devices=new JSONArray();
    final ArrayList<Button> buttons=new ArrayList<>();boolean pending,english,held,stickBlocked;View pressed;int stickDirection;long nextStick;
    String message="",deviceKey="",currentDevice="";long resendAfter;
    final Runnable tick=new Runnable(){public void run(){if(!hasWindowFocus()||stickDirection==0||stickBlocked)return;long now=SystemClock.uptimeMillis();if(now>=nextStick){move(stickDirection);nextStick=now+145;}ui.postDelayed(this,16);}};
    String t(String vi,String en){return english?en:vi;}
    int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);english="en".equals(getSharedPreferences("thorqol_ui",0).getString("language","vi"));
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);try{config=new LicenseConfig(this);deviceKey=DeviceIdentity.get(this);}catch(Exception e){message=t("Chưa đọc được cấu hình tài khoản.","Account configuration could not be loaded.");}
        if(config!=null&&config.ready())getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        render();if(config!=null&&config.ready()&&!token().isEmpty())load();
    }
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
    @Override protected void onPause(){ui.removeCallbacks(tick);held=false;pressed=null;stickDirection=0;super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){held=false;pressed=null;ui.removeCallbacks(tick);stickDirection=0;}}
    GradientDrawable background(int color,int border){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(10));if(border!=0)g.setStroke(dp(2),border);return g;}
    TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(7));v.setLineSpacing(dp(2),1);return v;}
    void label(String value){TextView v=text(value,20,WHITE);v.setTypeface(null,Typeface.BOLD);body.addView(v);}
    void note(String value){body.addView(text(value,14,MUTED));}
    EditText input(String hint,int type,String value){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setInputType(type);e.setText(value);e.setPadding(dp(14),dp(8),dp(14),dp(8));e.setBackground(background(0xff34404a,0));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(10);body.addView(e,lp);return e;}
    Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(PANEL);b.setTypeface(null,Typeface.BOLD);b.setTextSize(14);b.setSoundEffectsEnabled(false);b.setBackground(background(LIME,0));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setOnClickListener(v->{if(!pending)action.run();});
        b.setOnFocusChangeListener((v,focused)->{b.setBackground(background(focused?0xffc2fff0:LIME,focused?PINK:0));if(android.animation.ValueAnimator.areAnimatorsEnabled())b.animate().scaleX(focused?.985f:1).scaleY(focused?.985f:1).setDuration(140).start();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.bottomMargin=dp(10);body.addView(b,lp);buttons.add(b);b.setEnabled(!pending);return b;}
    String token(){return SecureStore.get(this,"token");}
    String emailSaved(){return LicenseAccess.account(this).optString("email",getSharedPreferences("thorqol_ui",0).getString("last_email",""));}
    void render(){
        held=false;pressed=null;stickBlocked=stickDirection!=0;ui.removeCallbacks(tick);buttons.clear();
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);root.setPadding(dp(28),dp(12),dp(28),dp(8));setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView brand=text("THORQOL / "+t("TÀI KHOẢN","ACCOUNT"),25,LIME);brand.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD_ITALIC));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView tag=text("$25 · "+t("VĨNH VIỄN · 2 MÁY","LIFETIME · 2 DEVICES"),13,WHITE);header.addView(tag);root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(22),dp(16),dp(22),dp(12));body.setBackground(background(PANEL,0));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=text(message,14,PINK);body.addView(status);
        boolean ready=config!=null&&config.ready()&&!deviceKey.isEmpty();
        if(!ready){label(t("ĐANG CHUẨN BỊ MỞ BÁN","PREPARING FOR LAUNCH"));note(t("Máy chủ tài khoản và xác nhận thanh toán chưa được kết nối. Bản này vẫn dùng các chức năng hiện có; chưa bật khóa trả phí.","The account server and payment verification are not connected yet. Existing app features remain available; paid access is not enabled."));
            label(t("COMBO 3 ỨNG DỤNG","3-APP COMBO"));note("Việt Hóa PC · Emu Game Store · Android Replay");note(t("25 USD một lần. Một tài khoản, tối đa 2 thiết bị cho cả combo. Cả ba app trên cùng một máy tính là 1 thiết bị.","USD 25 once. One account, up to 2 devices across the bundle. All three apps on one device use one slot."));
            note(t("Đăng nhập sẽ dùng email đã thanh toán Ko-fi, xác thực bằng mã email; không nhập mật khẩu Ko-fi vào app.","Sign-in will use the email from your Ko-fi purchase, verified by an email code. Do not enter your Ko-fi password in the app."));
            button(t("Quay lại ứng dụng","Back to app"),this::finish);
        }else if(token().isEmpty()){
            label(t("ĐĂNG NHẬP COMBO","SIGN IN TO YOUR BUNDLE"));note(t("Dùng đúng email đã mua combo trên Ko-fi. Mã xác nhận có 6 số, dùng một lần.","Use the email from your Ko-fi bundle purchase. The six-digit verification code is single use."));
            email=input(t("Email thanh toán Ko-fi","Ko-fi purchase email"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,emailSaved());
            Button request=button(t("Gửi mã đăng nhập","Send sign-in code"),this::requestCode);if(SystemClock.elapsedRealtime()<resendAfter)request.setEnabled(false);
            code=input(t("Mã xác nhận 6 số","Six-digit verification code"),InputType.TYPE_CLASS_NUMBER,"");code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
            button(t("Xác nhận & đăng nhập","Verify & sign in"),this::verifyCode);note(t("Mã có hiệu lực 10 phút. Gửi lại sau ít nhất 60 giây.","Codes expire after 10 minutes. Wait at least 60 seconds before requesting another."));
        }else{
            JSONObject account=LicenseAccess.account(this);label(account.optString("email"));boolean entitled=account.optBoolean("entitled",false);
            label(entitled?t("COMBO VĨNH VIỄN","LIFETIME BUNDLE"):t("CHƯA CÓ QUYỀN COMBO","NO BUNDLE PURCHASE"));
            if(!entitled){note(t("Chưa tìm thấy giao dịch hợp lệ cho email này. Nếu vừa thanh toán, kiểm tra lại sau hoặc liên hệ người bán.","No valid purchase was found for this email. If you just paid, refresh shortly or contact the seller."));button(t("Xem combo trên Ko-fi","View bundle on Ko-fi"),()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(config.shop))));}
            if(entitled){
                try{JSONObject grant=LicenseAccess.verified(this);currentDevice=grant.getString("device_id");long expiry=grant.getLong("exp")*1000;note(t("Đã kích hoạt máy này. Quyền mua không hết hạn; xác minh offline đến ","This device is activated. Your purchase does not expire; offline verification is valid until ")+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,english?Locale.US:new Locale("vi","VN")).format(new Date(expiry)));}
                catch(Exception ignored){currentDevice="";note(t("Máy này cần kích hoạt hoặc kiểm tra lại qua mạng.","This device needs activation or an online verification."));}
                name=input(t("Tên thiết bị","Device name"),InputType.TYPE_CLASS_TEXT,Build.MANUFACTURER+" "+Build.MODEL);
                button(t("Kích hoạt máy này / làm mới quyền","Activate this device / refresh access"),()->activate(null));
            }
            label(t("THIẾT BỊ ĐÃ KÍCH HOẠT","ACTIVATED DEVICES")+" ("+devices.length()+"/2)");
            for(int i=0;i<devices.length();i++){JSONObject d=devices.optJSONObject(i);if(d==null)continue;final String id=d.optString("id"),title=d.optString("name");
                note(title+(id.equals(currentDevice)?t(" · máy này"," · this device"):""));
                button(t("Ngắt ","Deactivate ")+title,()->confirm(t("Ngắt thiết bị?","Deactivate device?"),title+"\n\n"+t("Máy này mất quyền khi kiểm tra online; bản offline còn hạn chỉ dùng đến lúc hết hạn xác minh.","This device loses access on its next online check. An offline copy can work only until its signed verification expires."),()->revoke(id)));
            }
            button(t("Làm mới tài khoản & danh sách máy","Refresh account & devices"),this::load);
            button(t("Đăng xuất tài khoản","Sign out"),()->confirm(t("Đăng xuất?","Sign out?"),t("Đăng xuất không tự giải phóng suất thiết bị. Dùng Ngắt thiết bị nếu muốn chuyển sang máy khác.","Signing out does not free a device slot. Use Deactivate device to move to another device."),this::logout));
        }
        button(t("Đóng","Close"),this::onBackPressed);
        root.addView(text(t("D-pad / cần trái: di chuyển · A: chọn khi nhả · B: quay lại","D-pad / left stick: navigate · release A: select · B: back"),12,MUTED));
        if(pending)for(Button b:buttons)b.setEnabled(false);
    }
    interface Work{void run() throws Exception;}
    void run(Work task){if(pending)return;pending=true;status.setText(t("Đang kết nối…","Connecting…"));for(Button b:buttons)b.setEnabled(false);
        worker.execute(()->{try{task.run();}catch(ApiError e){if(Arrays.asList("LOGIN_REQUIRED","DEVICE_REVOKED","PURCHASE_REQUIRED","SESSION_DEVICE_MISMATCH").contains(e.code)){
                    SecureStore.remove(this,"grant");if("LOGIN_REQUIRED".equals(e.code)||"SESSION_DEVICE_MISMATCH".equals(e.code))LicenseAccess.clearSession(this);}
                message=error(e.code);if("DEVICE_LIMIT".equals(e.code)){JSONArray list=e.data.optJSONArray("devices");if(list!=null)devices=list;ui.post(this::replacement);}}
            catch(Exception e){message=t("Không kết nối hoặc xác minh được dịch vụ. Kiểm tra mạng rồi thử lại.","Could not connect to or verify the service. Check your connection and try again.");}
            finally{ui.post(()->{if(!isFinishing()&&!isDestroyed()){pending=false;render();}});}});
    }
    void requestCode(){final String mailbox=email.getText().toString().trim().toLowerCase(Locale.ROOT);if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mailbox).matches()){status.setText(error("INVALID_EMAIL"));return;}
        if(SystemClock.elapsedRealtime()<resendAfter)return;getSharedPreferences("thorqol_ui",0).edit().putString("last_email",mailbox).apply();
        run(()->{api("POST","/v1/auth/otp/request",new JSONObject().put("email",mailbox),false);resendAfter=SystemClock.elapsedRealtime()+60000;message=t("Đã gửi mã. Kiểm tra hộp thư và mục thư rác.","Code sent. Check your inbox and spam folder.");ui.postDelayed(()->{if(!isFinishing()&&!pending)render();},60000);});}
    void verifyCode(){final String mailbox=email.getText().toString().trim().toLowerCase(Locale.ROOT),digits=code.getText().toString();if(!digits.matches("[0-9]{6}")){status.setText(error("INVALID_CODE"));return;}
        run(()->{JSONObject result=api("POST","/v1/auth/otp/verify",new JSONObject().put("email",mailbox).put("code",digits),false);String access=result.getString("accessToken");if(!access.matches("[A-Za-z0-9_-]{43}"))throw new SecurityException("Invalid session");
            SecureStore.put(this,"token",access);SecureStore.put(this,"account",result.getJSONObject("account").toString());SecureStore.remove(this,"grant");devices=api("GET","/v1/devices",null,true).getJSONArray("devices");message=t("Đăng nhập thành công. Chọn kích hoạt máy này.","Signed in. Select Activate this device.");});}
    void load(){run(()->{JSONObject a=api("GET","/v1/account",null,true).getJSONObject("account");SecureStore.put(this,"account",a.toString());devices=api("GET","/v1/devices",null,true).getJSONArray("devices");
        if(a.optBoolean("entitled")&&!SecureStore.get(this,"grant").isEmpty())saveGrant(api("POST","/v1/license/refresh",new JSONObject().put("deviceKey",deviceKey).put("appId",getPackageName()),true));
        if(!a.optBoolean("entitled"))SecureStore.remove(this,"grant");message=t("Đã cập nhật tài khoản và danh sách thiết bị.","Account and devices refreshed.");});}
    void activate(String replace){final String title=name==null?Build.MODEL:name.getText().toString().trim();run(()->{JSONObject b=new JSONObject().put("deviceKey",deviceKey).put("name",title).put("appId",getPackageName());if(replace!=null)b.put("replaceDeviceId",replace);
        saveGrant(api("POST","/v1/devices/activate",b,true));devices=api("GET","/v1/devices",null,true).getJSONArray("devices");message=t("Đã kích hoạt. Cả ba app trên máy này dùng chung một suất.","Activated. All three apps on this device share one slot.");});}
    void saveGrant(JSONObject response)throws Exception{String grant=response.getString("license");long now=System.currentTimeMillis()/1000;LicenseGrant.verify(grant,config.spki,config.keyId,deviceKey,getPackageName(),LicenseAccess.account(this).getString("id"),now,0);
        SecureStore.put(this,"grant",grant);SecureStore.put(this,"device",response.getJSONObject("device").getString("id"));SecureStore.put(this,"last_seen",Long.toString(now));}
    void revoke(String id){run(()->{api("POST","/v1/devices/"+id+"/revoke",new JSONObject(),true);if(id.equals(SecureStore.get(this,"device")))LicenseAccess.clearSession(this);else devices=api("GET","/v1/devices",null,true).getJSONArray("devices");message=t("Đã ngắt thiết bị.","Device deactivated.");});}
    void logout(){run(()->{try{api("POST","/v1/auth/logout",new JSONObject(),true);}finally{LicenseAccess.clearSession(this);}message=t("Đã đăng xuất.","Signed out.");});}
    void replacement(){if(isFinishing())return;String[] labels=new String[devices.length()];for(int i=0;i<labels.length;i++)labels[i]=devices.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this).setTitle(t("Đã đủ 2 máy — chọn máy để thay","2-device limit — select a device to replace")).setItems(labels,(d,which)->{String id=devices.optJSONObject(which).optString("id");confirm(t("Thay thiết bị?","Replace device?"),labels[which]+" → "+Build.MODEL,()->activate(id));}).setNegativeButton(t("Hủy","Cancel"),null).show();}
    void confirm(String title,String details,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(details).setNegativeButton(t("Hủy","Cancel"),null).setPositiveButton(t("Xác nhận","Confirm"),(d,w)->action.run()).show();}
    JSONObject api(String method,String path,JSONObject body,boolean auth)throws Exception{return request(this,config,method,path,body,auth);}
    static JSONObject request(android.content.Context context,LicenseConfig config,String method,String path,JSONObject body,boolean auth)throws Exception{
        if(config==null||!config.ready())throw new SecurityException("Not configured");HttpURLConnection conn=(HttpURLConnection)new URL(config.api+path).openConnection();
        conn.setRequestMethod(method);conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(20000);conn.setReadTimeout(20000);conn.setUseCaches(false);conn.setRequestProperty("Accept","application/json");conn.setRequestProperty("Cache-Control","no-store");
        if(auth){String session=SecureStore.get(context,"token");if(session.isEmpty())throw new ApiError("LOGIN_REQUIRED",new JSONObject());conn.setRequestProperty("Authorization","Bearer "+session);}
        try{if(body!=null){byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);conn.setDoOutput(true);conn.setFixedLengthStreamingMode(payload.length);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=conn.getOutputStream()){out.write(payload);}}
            int status=conn.getResponseCode();if(status>=300&&status<400)throw new SecurityException("Redirect refused");InputStream input=status<400?conn.getInputStream():conn.getErrorStream();if(input==null)throw new IOException("Empty response");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try{byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>262144)throw new IOException("Response too large");bytes.write(buffer,0,n);}}finally{input.close();}
            JSONObject result=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));if(status<200||status>=300)throw new ApiError(result.optJSONObject("error")==null?"SERVICE_UNAVAILABLE":result.getJSONObject("error").optString("code"),result);return result;
        }finally{conn.disconnect();}
    }
    static final class ApiError extends Exception{final String code;final JSONObject data;ApiError(String c,JSONObject d){super(c);code=c;data=d;}}
    String error(String id){switch(id){
        case "INVALID_EMAIL":return t("Nhập email hợp lệ.","Enter a valid email address.");
        case "INVALID_CODE":return t("Mã sai, đã hết hạn hoặc đã dùng. Mã gồm 6 số.","The code is incorrect, expired or already used. Enter six digits.");
        case "RATE_LIMITED":return t("Quá nhiều yêu cầu. Chờ trước khi thử lại.","Too many requests. Wait before trying again.");
        case "EMAIL_UNAVAILABLE":return t("Chưa gửi được email. Thử lại sau.","Email could not be sent. Try again later.");
        case "LOGIN_REQUIRED":return t("Phiên đăng nhập hết hạn hoặc bị ngắt. Đăng nhập lại.","Your session expired or was revoked. Sign in again.");
        case "PURCHASE_REQUIRED":return t("Email này chưa có giao dịch combo hợp lệ.","No valid bundle purchase was found for this email.");
        case "DEVICE_LIMIT":return t("Đã đủ 2 thiết bị. Chọn máy cũ để thay.","Two devices are already active. Choose one to replace.");
        case "DEVICE_REVOKED":return t("Máy này chưa kích hoạt hoặc đã bị ngắt.","This device is not activated or was deactivated.");
        case "SESSION_DEVICE_MISMATCH":return t("Phiên thuộc máy khác. Đăng nhập lại trên máy này.","This session belongs to another device. Sign in again here.");
        case "INVALID_DEVICE_NAME":return t("Tên máy phải dài từ 1 đến 80 ký tự.","Device names must be 1–80 characters long.");
        default:return t("Dịch vụ chưa sẵn sàng. Vui lòng thử lại sau.","The service is unavailable. Please try again later.");}}
    void move(int key){long now=SystemClock.uptimeMillis();super.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,key,0));super.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,key,0));}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if(e.isFromSource(InputDevice.SOURCE_JOYSTICK)&&e.getActionMasked()==MotionEvent.ACTION_MOVE){float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y);if(Math.max(Math.abs(x),Math.abs(y))<.35f){stickDirection=0;stickBlocked=false;ui.removeCallbacks(tick);return true;}int direction=Math.max(Math.abs(x),Math.abs(y))<.55f?0:Math.abs(x)>Math.abs(y)?(x<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT):(y<0?KeyEvent.KEYCODE_DPAD_UP:KeyEvent.KEYCODE_DPAD_DOWN);
        if(direction!=0&&direction!=stickDirection&&!stickBlocked){stickDirection=direction;move(direction);nextStick=SystemClock.uptimeMillis()+380;ui.removeCallbacks(tick);ui.post(tick);}return true;}return super.dispatchGenericMotionEvent(e);}
    @Override public boolean dispatchKeyEvent(KeyEvent e){int k=e.getKeyCode();if(k==KeyEvent.KEYCODE_BUTTON_A){if(e.getAction()==KeyEvent.ACTION_DOWN){if(e.getRepeatCount()==0){held=true;pressed=getCurrentFocus();}}else{boolean click=held&&pressed!=null&&pressed==getCurrentFocus()&&!e.isCanceled();View target=pressed;held=false;pressed=null;if(click)target.performClick();}return true;}
        if(k==KeyEvent.KEYCODE_BUTTON_B){if(e.getAction()==KeyEvent.ACTION_UP&&!e.isCanceled())onBackPressed();return true;}return super.dispatchKeyEvent(e);}
    @Override public void onBackPressed(){
        if(config!=null&&config.enforce){
            if(LicenseAccess.canUse(this)&&getIntent().getBooleanExtra("return_to_app",false)){
                Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(launch);}
            }
            finish();return;
        }
        super.onBackPressed();
    }
}
