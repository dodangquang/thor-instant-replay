package com.thorqol.shared;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import java.security.MessageDigest;
import java.security.SecureRandom;
import android.util.Base64;

/** Shares an opaque identity only with the three exact first-party packages and signing certificates. */
public final class DeviceIdentity {
    static final String[] PACKAGES={"vn.vhbridge","com.khogame.thor","com.thor.instantreplay"};
    static final String[] CERTS={
        "d2e3f054d1c9a7120537caf021aba0d414193eb7101b146d5606eae2ea4699bb",
        "06c96a505c22eeec42ec8a62f2b822f527667fbdc9ad62f95f2f0590e93b099b",
        "06c96a505c22eeec42ec8a62f2b822f527667fbdc9ad62f95f2f0590e93b099b"};
    private DeviceIdentity(){}
    @SuppressWarnings("deprecation") static boolean trusted(Context c,String packageName){
        try {
            int i=java.util.Arrays.asList(PACKAGES).indexOf(packageName);if(i<0)return false;
            PackageInfo info=c.getPackageManager().getPackageInfo(packageName,PackageManager.GET_SIGNATURES);
            if(info.signatures==null||info.signatures.length!=1)return false;
            return CERTS[i].equals(hex(MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray())));
        }catch(Exception error){return false;}
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    static synchronized Bundle local(Context c,Bundle candidate) throws Exception {
        String value=SecureStore.get(c,"identity");long created=c.getSharedPreferences("thorqol_identity",0).getLong("created",Long.MAX_VALUE);
        if(candidate!=null){String proposed=candidate.getString("key","");long when=candidate.getLong("created",Long.MAX_VALUE);
            if(proposed.matches("[A-Za-z0-9_-]{43}")&&when>0&&(value.isEmpty()||when<created||(when==created&&proposed.compareTo(value)<0))){
                SecureStore.put(c,"identity",proposed);created=when;value=proposed;
                c.getSharedPreferences("thorqol_identity",0).edit().putLong("created",created).commit();
            }
        }
        Bundle result=new Bundle();result.putString("key",value);result.putLong("created",created);return result;
    }
    private static Bundle call(Context c,String pkg,String method,Bundle data){
        try{if(!trusted(c,pkg))return null;return c.getContentResolver().call(Uri.parse("content://"+pkg+".thorqol.identity"),method,null,data);}catch(Exception e){return null;}
    }
    private static Bundle older(Bundle a,Bundle b){
        if(b==null||b.getString("key","").isEmpty())return a;if(a==null||a.getString("key","").isEmpty())return b;
        long x=a.getLong("created",Long.MAX_VALUE),y=b.getLong("created",Long.MAX_VALUE);
        return y<x||y==x&&b.getString("key").compareTo(a.getString("key"))<0?b:a;
    }
    public static String get(Context context) throws Exception {
        Context c=context.getApplicationContext();Bundle best=local(c,null);
        for(String pkg:PACKAGES)best=older(best,call(c,pkg,"peek",null));
        if(best.getString("key","").isEmpty()){
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);best=new Bundle();
            best.putString("key",Base64.encodeToString(random,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING));best.putLong("created",System.currentTimeMillis());
        }
        // Two passes settle simultaneous first launches and propagate the oldest surviving identity.
        for(int pass=0;pass<2;pass++)for(String pkg:PACKAGES)best=older(best,call(c,pkg,"adopt",best));
        best=local(c,best);return best.getString("key");
    }
}
