package com.thor.instantreplay.share;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Host regression test of the same read-only resolver used by both LAN routes and the list. */
public final class ClipFilePolicyTest {
    private static int checks;
    private static boolean symlinkTested;
    private static final List<Path> owned = new ArrayList<>();
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label); checks++;
    }
    private static Path directory(Path path) throws Exception { Files.createDirectory(path); owned.add(path); return path; }
    private static File file(Path path) throws Exception { Files.write(path, new byte[]{1,2,3,4}); owned.add(path); return path.toFile(); }
    private static String decoded(String encoded) throws Exception { return URLDecoder.decode(encoded, "UTF-8"); }
    public static int getChecks() { return checks; }
    public static boolean wasSymlinkTested() { return symlinkTested; }
    public static void main(String[] args) throws Exception {
        Path parent = new File(args[0]).getCanonicalFile().toPath();
        Files.createDirectories(parent);
        Path scratch = Files.createTempDirectory(parent, "clip-policy-").toRealPath(); owned.add(scratch);
        try {
            Path root = directory(scratch.resolve("ThorClips"));
            File outside = file(scratch.resolve("outside.mp4"));
            File ordinary = file(root.resolve("Clip_20261018_194124.mp4"));
            File upper = file(root.resolve("RECORD.MP4"));
            String unicodeName = "Pha đẹp + 100% clip & phần 2.MP4";
            File unicode = file(root.resolve(unicodeName));
            check(ordinary.getCanonicalFile().equals(ClipFilePolicy.resolve(root.toFile(), ordinary.getName())), "normal clip");
            check(upper.getCanonicalFile().equals(ClipFilePolicy.resolve(root.toFile(), upper.getName())), "uppercase MP4");
            check(unicode.getCanonicalFile().equals(ClipFilePolicy.resolve(root.toFile(), unicodeName)), "spaces, Unicode, plus, literal percent");
            check(unicode.getCanonicalFile().equals(ClipFilePolicy.resolve(root.toFile(), decoded(ClipFilePolicy.queryName(unicodeName)))), "generated link query round trip");
            check(ClipFilePolicy.resolve(root.toFile(), "../outside.mp4") == null, "parent traversal");
            check(ClipFilePolicy.resolve(root.toFile(), "..\\outside.mp4") == null, "backslash traversal");
            check(ClipFilePolicy.resolve(root.toFile(), outside.getAbsolutePath()) == null, "absolute file outside root");
            check(ClipFilePolicy.resolve(root.toFile(), decoded("%2e%2e%2foutside.mp4")) == null, "encoded slash traversal");
            check(ClipFilePolicy.resolve(root.toFile(), decoded("%2e%2e%5coutside.mp4")) == null, "encoded backslash traversal");
            file(root.resolve("%2e%2e%2foutside.mp4"));
            check(ClipFilePolicy.resolve(root.toFile(), decoded("%252e%252e%252foutside.mp4")) == null, "double encoded traversal even if literal file exists");
            file(root.resolve("%252e%252e%252foutside.mp4"));
            check(ClipFilePolicy.resolve(root.toFile(), decoded("%25252e%25252e%25252foutside.mp4")) == null, "third encoding layer");
            Path nested = directory(root.resolve("nested")); file(nested.resolve("inside.mp4"));
            check(ClipFilePolicy.resolve(root.toFile(), "nested/inside.mp4") == null, "nested path is not a direct clip");
            directory(root.resolve("folder.mp4"));
            check(ClipFilePolicy.resolve(root.toFile(), "folder.mp4") == null, "directory with MP4 extension");
            file(root.resolve("other.txt"));
            check(ClipFilePolicy.resolve(root.toFile(), "other.txt") == null, "non-MP4");
            check(ClipFilePolicy.resolve(root.toFile(), "missing.mp4") == null, "missing file");
            check(ClipFilePolicy.resolve(root.toFile(), "clip\u0000.mp4") == null, "NUL rejected without exception");
            check(ClipFilePolicy.resolve(root.toFile(), "clip\r\nInjected.mp4") == null, "header control characters");
            check(ClipFilePolicy.resolve(root.toFile(), null) == null, "null name");
            check(ClipFilePolicy.resolve(root.toFile(), "") == null, "empty name");
            check(ClipFilePolicy.resolve(root.resolve("missing").toFile(), ordinary.getName()) == null, "missing root");
            check("&lt;clip &amp; &#39;one&#39; &quot;two&quot;&gt;.mp4".equals(ClipFilePolicy.htmlName("<clip & 'one' \"two\">.mp4")), "display text escaped");
            check("clip \\\"one\\\".mp4".equals(ClipFilePolicy.dispositionName("clip \"one\".mp4")), "quoted header filename escaped");
            try {
                Path link = root.resolve("escape.mp4"); Files.createSymbolicLink(link, outside.toPath()); owned.add(link);
                check(ClipFilePolicy.resolve(root.toFile(), link.getFileName().toString()) == null, "symlink escaping canonical root");
                symlinkTested = true;
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                System.out.println("SKIP symlink creation: host did not allow a symbolic link");
            }
            System.out.println("PASS " + checks + " clip-sharing boundary checks");
        } finally {
            // Remove only exact paths this test created, under its verified scratch boundary.
            for (int i=owned.size()-1;i>=0;i--) {
                Path path=owned.get(i).toAbsolutePath().normalize();
                if (!path.startsWith(scratch)) throw new SecurityException("Cleanup escaped test scratch");
                Files.deleteIfExists(path);
            }
        }
    }
}
