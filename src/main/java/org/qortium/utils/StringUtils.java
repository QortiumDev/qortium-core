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
}
