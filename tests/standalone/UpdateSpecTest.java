import com.thorqol.shared.UpdateSpec;
public final class UpdateSpecTest {
    static int checks;
    static final String HASH=new String(new char[64]).replace('\0','a');
    static final String BASE="https://github.com/dodangquang/thor-instant-replay/releases/download/thorqol-v1.2.1-replay-v2.9.1/";
    interface Action {void run();}
    static void reject(Action action){try{action.run();throw new AssertionError("Accepted invalid update");}catch(IllegalArgumentException expected){checks++;}}
    static UpdateSpec spec(String expected,String pkg,long code,String version,int sdk,long bytes,String hash,String url){return new UpdateSpec(expected,pkg,code,version,sdk,bytes,hash,url,"Release notes");}
    public static void main(String[] args){
        for(String pkg:new String[]{"vn.vhbridge","vn.vhbridge.trial","com.khogame.thor","com.khogame.thor.trial","com.thor.instantreplay","com.thor.instantreplay.trial"}){
            String version=(pkg.startsWith("com.thor.instantreplay")?"2.9.1":"1.2.1")+(pkg.endsWith(".trial")?"-trial":"");String url=BASE+UpdateSpec.filename(pkg,version);
            UpdateSpec valid=spec(pkg,pkg,100,version,29,1000,HASH,url);if(!valid.packageName.equals(pkg)||!UpdateSpec.manifest(pkg).endsWith("/"+pkg+".json"))throw new AssertionError();checks++;
            reject(()->spec(pkg,pkg+".wrong",100,version,29,1000,HASH,url));
            reject(()->spec(pkg,pkg,0,version,29,1000,HASH,url));reject(()->spec(pkg,pkg,2100000001L,version,29,1000,HASH,url));
            reject(()->spec(pkg,pkg,100,version,25,1000,HASH,url));reject(()->spec(pkg,pkg,100,version,29,UpdateSpec.MAX_APK+1,HASH,url));
            reject(()->spec(pkg,pkg,100,version,29,1000,"bad-hash",url));reject(()->spec(pkg,pkg,100,version,29,1000,HASH,url+"?token=bad"));
            reject(()->spec(pkg,pkg,100,version,29,1000,HASH,url.replace("github.com","github.com.evil.invalid")));
            reject(()->spec(pkg,pkg,100,version,29,1000,HASH,url.replace("https:","http:")));
            reject(()->spec(pkg,pkg,100,version,29,1000,HASH,url.replace("dodangquang/","other/")));
            reject(()->spec(pkg,pkg,100,version,29,1000,HASH,url.replace("thor-instant-replay/","other/")));
            reject(()->spec(pkg,pkg,100,version,29,1000,HASH,url+"#fragment"));
            reject(()->spec(pkg,pkg,100,pkg.endsWith(".trial")?"1.2.1":"1.2.1-trial",29,1000,HASH,url));
            reject(()->spec(pkg,pkg,100,version,29,1000,HASH,BASE+"../"+UpdateSpec.filename(pkg,version)));
        }
        reject(()->UpdateSpec.manifest("other.package"));System.out.println("UpdateSpec: "+checks+" checks passed");
    }
}
