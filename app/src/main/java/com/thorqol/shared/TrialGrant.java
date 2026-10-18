package com.thorqol.shared;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Trial claims are a different protocol from lifetime account grants. */
public final class TrialGrant {
    private TrialGrant() {}
    private static long number(JSONObject p,String key)throws Exception{Object value=p.get(key);if(!(value instanceof Integer||value instanceof Long))throw new SecurityException("Invalid trial numeric claim");return ((Number)value).longValue();}
    public static JSONObject verify(String token,String spki,String kid,String app,String device) throws Exception {
        if(token==null||token.length()>16384)throw new SecurityException("Invalid trial grant");
        String[] parts=token.split("\\.",-1);
        if(parts.length!=3)throw new SecurityException("Invalid trial grant");
        for(String part:parts)if(!part.matches("[A-Za-z0-9_-]+"))throw new SecurityException("Invalid encoding");
        JSONObject header=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));
        if(!"RS256".equals(header.optString("alg"))||!"JWT".equals(header.optString("typ"))||!kid.equals(header.optString("kid"))||header.has("crit"))throw new SecurityException("Invalid trial header");
        java.security.PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spki)));
        if(!(key instanceof java.security.interfaces.RSAPublicKey)||((java.security.interfaces.RSAPublicKey)key).getModulus().bitLength()<2048)throw new SecurityException("Invalid trial key");
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);
        verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        if(!verifier.verify(Base64.getUrlDecoder().decode(parts[2])))throw new SecurityException("Invalid trial signature");
        JSONObject p=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8));
        long issued=number(p,"iat"),expiry=number(p,"exp"),started=number(p,"trial_started_at"),used=number(p,"downloads_used");
        if(number(p,"v")!=1||!"thorqol".equals(p.optString("iss"))||!"device-trial".equals(p.optString("plan"))||!app.equals(p.optString("app_id"))||!device.equals(p.optString("device_hash")))throw new SecurityException("Wrong trial subject");
        if(issued<=0||started<=0||started>issued||expiry<=issued||expiry-issued>86400)throw new SecurityException("Invalid trial times");
        if("com.thor.instantreplay".equals(app)){
            if(!p.has("download_limit")||!p.isNull("download_limit")||used!=0||p.isNull("trial_expires_at")||number(p,"trial_expires_at")!=expiry||expiry-started!=86400)throw new SecurityException("Invalid Replay trial");
        }else{
            if(!("vn.vhbridge".equals(app)||"com.khogame.thor".equals(app))||!p.has("trial_expires_at")||!p.isNull("trial_expires_at")||number(p,"download_limit")!=3||used<0||used>3||expiry-issued!=86400)throw new SecurityException("Invalid download trial");
        }
        return p;
    }
}
