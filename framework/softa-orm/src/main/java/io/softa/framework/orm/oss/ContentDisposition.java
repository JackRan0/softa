package io.softa.framework.orm.oss;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;

/**
 * The {@code Content-Disposition} a signed URL asks the storage to answer with.
 *
 * <p>The file name has to travel in this header. Without it the browser names the file after the last
 * segment of the URL, which is the object key — and the key is not the file's name: it is a generated
 * path, so it does not grow with whatever name the user's file carried.
 */
public final class ContentDisposition {

    /** Save the file rather than open it. */
    public static final String ATTACHMENT = "attachment";

    /** Open the file in the browser where it can be shown. */
    public static final String INLINE = "inline";

    private ContentDisposition() {
    }

    /**
     * {@code type; filename="…"; filename*=UTF-8''…}: the plain {@code filename} for clients that only
     * read ASCII, the RFC 5987 {@code filename*} for every other — it carries any character, so a name in
     * Chinese, or with spaces, is saved as it was uploaded. A blank name gives the bare type.
     */
    public static String of(String type, String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return type + ";";
        }
        // URLEncoder leaves '*' as it is, but RFC 5987 does not allow it in a value.
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
        return type + "; filename=\"" + asciiFallback(fileName) + "\"; filename*=UTF-8''" + encoded;
    }

    /** Characters a quoted ASCII filename cannot carry become {@code _}. */
    static String asciiFallback(String fileName) {
        StringBuilder out = new StringBuilder(fileName.length());
        for (char c : fileName.toCharArray()) {
            out.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        return out.toString();
    }
}
