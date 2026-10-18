package com.thorqol.shared;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private, non-backed-up credentials encrypted with this app's Android Keystore key. */
final class SecureStore {
    private static final String ALIAS="thorqol.account.v1";
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    static synchronized void put(Context c,String name,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encoded=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!c.getSharedPreferences("thorqol_secrets",0).edit().putString(name,encoded).commit())throw new java.io.IOException("Secure storage failed");
    }
    static synchronized String get(Context c,String name) {
        try {
            String saved=c.getSharedPreferences("thorqol_secrets",0).getString(name,"");if(saved.isEmpty())return "";
            String[] parts=saved.split("\\.",-1);if(parts.length!=2)return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(parts[1],Base64.DEFAULT)),StandardCharsets.UTF_8);
        }catch(Exception error){return "";}
    }
    static void remove(Context c,String name){c.getSharedPreferences("thorqol_secrets",0).edit().remove(name).commit();}
}
