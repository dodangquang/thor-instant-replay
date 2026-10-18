package android.os;
import java.io.File;
/** Host harness only; never packaged into either APK. */
public final class Environment {
    public static final String DIRECTORY_MOVIES = "Movies";
    public static File getExternalStoragePublicDirectory(String type) {
        return new File(System.getProperty("clip.test.movies"));
    }
}
