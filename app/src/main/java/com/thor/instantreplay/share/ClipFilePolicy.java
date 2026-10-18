package com.thor.instantreplay.share;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Read-only LAN sharing: only a direct MP4 child of the canonical clip directory. */
public final class ClipFilePolicy {
    private static final Pattern ENCODED_PATH_OR_CONTROL =
            Pattern.compile("%(?:25|2f|5c|0[0-9a-f]|1[0-9a-f]|7f)", Pattern.CASE_INSENSITIVE);
    private ClipFilePolicy() {}

    /** The query parser has already decoded the filename once. Never treat it as a relative path. */
    public static File resolve(File clipsDirectory, String decodedName) {
        if (clipsDirectory == null || decodedName == null || decodedName.isEmpty()
                || !decodedName.toLowerCase(Locale.ROOT).endsWith(".mp4")) return null;
        for (int i = 0; i < decodedName.length(); i++) {
            char c = decodedName.charAt(i);
            if (c == '/' || c == '\\' || c < 32 || c == 127) return null;
        }
        // Reject residual encoded separators, controls and another encoding layer.
        // Ordinary spaces, Unicode, '+' and literal '%' remain valid filename characters.
        if (ENCODED_PATH_OR_CONTROL.matcher(decodedName).find()) return null;
        try {
            File root = clipsDirectory.getCanonicalFile();
            File clip = new File(root, decodedName).getCanonicalFile();
            if (!root.isDirectory() || !root.equals(clip.getParentFile()) || !clip.isFile()
                    || !clip.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")) return null;
            return clip;
        } catch (IOException | SecurityException invalidPath) {
            return null;
        }
    }

    public static String queryName(String filename) {
        try { return URLEncoder.encode(filename, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    public static String htmlName(String filename) {
        return filename.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String dispositionName(String filename) {
        return filename.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
