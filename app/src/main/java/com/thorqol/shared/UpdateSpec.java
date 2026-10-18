package com.thorqol.shared;

import java.net.URI;

/** Pure-Java bounds and exact full/trial channel binding, also used by JVM tests. */
public final class UpdateSpec {
    public static final String ORIGIN="https://thorqol-license.netlify.app";
    public static final long MAX_APK=128L*1024*1024;
    public final String packageName,versionName,url,sha256,notes;public final long versionCode,bytes;public final int minSdk;
    public UpdateSpec(String expected,String pkg,long code,String version,int sdk,long size,String hash,String apk,String copy){
        if(!known(expected)||!expected.equals(pkg)||code<1||code>2100000000L||version==null||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"+(expected.endsWith(".trial")?"-trial":""))||sdk<26||sdk>100||size<1||size>MAX_APK||hash==null||!hash.matches("[a-fA-F0-9]{64}")||copy==null||copy.length()>4096)throw new IllegalArgumentException("Invalid update metadata");
        try{URI u=new URI(apk);String filename=filename(expected,version);if(!"https".equals(u.getScheme())||!"github.com".equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!u.getRawPath().matches("/dodangquang/thor-instant-replay/releases/download/thorqol-[0-9A-Za-z._-]+/"+java.util.regex.Pattern.quote(filename)))throw new Exception();}catch(Exception e){throw new IllegalArgumentException("Untrusted APK URL");}
        packageName=pkg;versionCode=code;versionName=version;minSdk=sdk;bytes=size;sha256=hash.toLowerCase(java.util.Locale.ROOT);url=apk;notes=copy;
    }
    public static boolean known(String pkg){return pkg!=null&&(pkg.equals("vn.vhbridge")||pkg.equals("vn.vhbridge.trial")||pkg.equals("com.khogame.thor")||pkg.equals("com.khogame.thor.trial")||pkg.equals("com.thor.instantreplay")||pkg.equals("com.thor.instantreplay.trial"));}
    public static String filename(String pkg,String version){return (pkg.startsWith("vn.vhbridge")?"Viet-Hoa-PC-":pkg.startsWith("com.khogame.thor")?"Emu-Game-Store-":"Android-Replay-")+version+".apk";}
    public static String manifest(String pkg){if(!known(pkg))throw new IllegalArgumentException("Unknown app");return ORIGIN+"/updates/v1/"+pkg+".json";}
}
