package com.thorqol.shared;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Verifies the pinned key and every authorization claim before an offline lease is accepted. */
public final class LicenseGrant {
    public static JSONObject verify(String compact,String spki,String kid,String deviceKey,String appId,String accountId,long nowSeconds,long lastSeenSeconds) throws Exception {
        if(compact==null||compact.length()>16384)throw new SecurityException("Invalid grant");
        String[] p=compact.split("\\.",-1);if(p.length!=3)throw new SecurityException("Invalid grant");
        for(String part:p)if(!part.matches("[A-Za-z0-9_-]+"))throw new SecurityException("Invalid encoding");
        JSONObject head=new JSONObject(new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8));
        if(!"RS256".equals(head.optString("alg"))||!"JWT".equals(head.optString("typ"))||!kid.equals(head.optString("kid"))||head.has("crit"))throw new SecurityException("Invalid key or algorithm");
        java.security.PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spki)));
        if(!(key instanceof java.security.interfaces.RSAPublicKey)||((java.security.interfaces.RSAPublicKey)key).getModulus().bitLength()<2048)throw new SecurityException("Invalid public key");
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);signature.update((p[0]+"."+p[1]).getBytes(StandardCharsets.US_ASCII));
        if(!signature.verify(Base64.getUrlDecoder().decode(p[2])))throw new SecurityException("Invalid signature");
        JSONObject payload=new JSONObject(new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8));
        String hash=DeviceIdentity.hex(MessageDigest.getInstance("SHA-256").digest(deviceKey.getBytes(StandardCharsets.UTF_8)));
        long issued=payload.getLong("iat"),expires=payload.getLong("exp");boolean app=false;JSONArray apps=payload.getJSONArray("app_ids");for(int i=0;i<apps.length();i++)if(appId.equals(apps.getString(i)))app=true;
        if(payload.getInt("v")!=1||!"thorqol".equals(payload.optString("iss"))||!"combo-lifetime".equals(payload.optString("plan"))||!accountId.equals(payload.optString("sub"))||!hash.equals(payload.optString("device_key_hash"))||!app||payload.optString("device_id").isEmpty())throw new SecurityException("Wrong grant subject");
        if(nowSeconds+300<issued||nowSeconds>=expires||expires<=issued||expires-issued>30*86400L||nowSeconds+300<lastSeenSeconds)throw new SecurityException("Grant expired or clock changed");
        return payload;
    }
}
