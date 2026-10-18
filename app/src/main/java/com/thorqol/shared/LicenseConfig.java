package com.thorqol.shared;

import android.content.Context;
import org.json.JSONObject;
import java.net.URI;

final class LicenseConfig {
    final String api,keyId,spki,shop;final boolean enforce;
    LicenseConfig(Context c) throws Exception{
        java.io.InputStream input=c.getAssets().open("thorqol-license.json");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try{byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>16384)throw new java.io.IOException("Config too large");bytes.write(buffer,0,n);}}finally{input.close();}
        JSONObject data=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        api=data.optString("apiBase","");keyId=data.optString("keyId","");spki=data.optString("publicKeySpki","");shop=data.optString("shopUrl","https://ko-fi.com/thorqol/shop");enforce=data.optBoolean("enforce",false);
        if(!api.isEmpty()){URI u=new URI(api);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getPort()!=-1||u.getRawQuery()!=null||u.getRawFragment()!=null||!"".equals(u.getRawPath()))throw new SecurityException("Invalid license origin");}
        if(!shop.equals("https://ko-fi.com/thorqol/shop")&&!shop.matches("https://ko-fi\\.com/s/[a-zA-Z0-9]+"))throw new SecurityException("Invalid shop URL");
    }
    boolean ready(){return !api.isEmpty()&&!keyId.isEmpty()&&!spki.isEmpty();}
}
