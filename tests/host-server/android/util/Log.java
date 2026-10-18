package android.util;
/** Host harness only; the actual server implementation is compiled by Gradle. */
public final class Log {
    public static int i(String tag,String message){return 0;}
    public static int w(String tag,String message){return 0;}
    public static int w(String tag,String message,Throwable error){return 0;}
    public static int e(String tag,String message,Throwable error){return 0;}
}
