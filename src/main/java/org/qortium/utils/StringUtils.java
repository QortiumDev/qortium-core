package org.qortium.utils;

public class StringUtils {

    public static String sanitizeString(String input) {
        if (input == null) {
            throw new NullPointerException("input");
        }

        StringBuilder cleaned = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); ++i) {
            char c = input.charAt(i);
            if (!isInvalidFilenameCharacter(c)) {
                cleaned.append(c);
            }
        }

        int start = 0;
        int end = cleaned.length();
        while (start < end && Character.isWhitespace(cleaned.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(cleaned.charAt(end - 1))) {
            end--;
        }

        StringBuilder sanitized = new StringBuilder(end - start);
        boolean previousWhitespace = false;
        for (int i = start; i < end; ++i) {
            char c = cleaned.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!previousWhitespace) {
                    sanitized.append('_');
                    previousWhitespace = true;
                }
            } else {
                sanitized.append(c);
                previousWhitespace = false;
            }
        }

        return sanitized.toString();
    }

    private static boolean isInvalidFilenameCharacter(char c) {
        return c == '<' || c == '>' || c == ':' || c == '"' || c == '/' || c == '\\' || c == '|' || c == '?' || c == '*';
    }

    /**
     * Format a byte count into a human-readable string using binary units (KB, MB, GB, ...).
     *
     * @param bytes the number of bytes
     * @return a string such as "51.6 GB" or "512 bytes"
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }

        String[] units = { "KB", "MB", "GB", "TB", "PB", "EB" };
        int exponent = (int) (Math.log(bytes) / Math.log(1024));
        exponent = Math.min(exponent, units.length); // guard against overflow beyond EB

        double value = bytes / Math.pow(1024, exponent);
        return String.format("%.2f %s", value, units[exponent - 1]);
    }
}
