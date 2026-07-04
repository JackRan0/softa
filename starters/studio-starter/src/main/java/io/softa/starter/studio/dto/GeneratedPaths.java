package io.softa.starter.studio.dto;

/**
 * Shared normalization for generated-code relative paths (studio code generation).
 * <p>
 * {@link #stripLeadingRelative(String)} is the common core of every generated-path
 * normalization: the sub-directory, the file name, and the assembled relative path all
 * start from it, then layer their own extra rules (collapse {@code //}, reject {@code ..},
 * reject blank, reject embedded separators) on top.
 */
public final class GeneratedPaths {

    private GeneratedPaths() {
    }

    /**
     * Trim, unify separators to {@code /}, and strip any leading {@code ./} and {@code /} segments.
     * Null-safe ({@code null} → {@code ""}).
     */
    public static String stripLeadingRelative(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
