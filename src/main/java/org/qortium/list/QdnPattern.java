package org.qortium.list;

import org.qortium.crypto.Crypto;

import java.util.Locale;

/**
 * A single gitignore-style pattern for the {@code followedQdn} / {@code blockedQdn} lists.
 * <p>
 * A pattern targets a QDN resource by its {@code SERVICE/NAME/IDENTIFIER} triple, with the
 * {@code *} wildcard matching any run of characters within a single segment. Trailing segments
 * may be omitted, in which case they match anything. Examples:
 * <ul>
 *     <li>{@code BLOG_POST} &mdash; any blog post (service only)</li>
 *     <li>{@code APP/BOB} &mdash; any app published under the name BOB</li>
 *     <li>{@code VIDEO/*&#47;cat*} &mdash; any video whose identifier starts with {@code cat}</li>
 *     <li>{@code *&#47;TOM} &mdash; any resource published under the name TOM</li>
 *     <li>{@code *&#47;*&#47;*fish*} &mdash; any resource whose identifier contains {@code fish}</li>
 * </ul>
 * The NAME segment may also be a Qortium address, in which case it is an alias for "any name
 * owned by that address". Address aliases are resolved to concrete names by {@link QdnFilter};
 * a bare single-segment address (e.g. {@code Qabc...}) is treated as {@code *&#47;<address>}.
 * <p>
 * An entry beginning with {@code !} is a <i>negation</i> (an exception): within a list, the last
 * pattern that matches a resource decides the outcome, so a negation can re-admit something an
 * earlier pattern matched (e.g. {@code VIDEO} then {@code !VIDEO/BOB} blocks all videos except
 * BOB's). A literal leading {@code !} can be written as {@code \!}.
 * <p>
 * Matching is case-insensitive for the SERVICE and NAME segments (Qortium names are
 * case-insensitive, service names are upper-snake constants) and case-sensitive for the
 * IDENTIFIER segment.
 */
public class QdnPattern {

    private final String serviceGlob;     // null = match any service
    private final String nameGlob;        // null = match any name
    private final String identifierGlob;  // null = match any identifier
    private final String addressAlias;    // non-null when the NAME segment is an address
    private final boolean negated;        // true for a "!" exception entry

    private QdnPattern(String serviceGlob, String nameGlob, String identifierGlob, String addressAlias, boolean negated) {
        this.serviceGlob = serviceGlob;
        this.nameGlob = nameGlob;
        this.identifierGlob = identifierGlob;
        this.addressAlias = addressAlias;
        this.negated = negated;
    }

    /**
     * Parse a raw list entry into a pattern, or return {@code null} if the entry is blank.
     */
    public static QdnPattern parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        boolean negated = false;
        if (trimmed.startsWith("!")) {
            negated = true;
            trimmed = trimmed.substring(1);
        } else if (trimmed.startsWith("\\!")) {
            // Escaped literal leading '!'
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }

        String[] parts = trimmed.split("/", -1);

        // Single-segment entry: a bare address means "any name owned by this address";
        // otherwise it targets a service (e.g. "BLOG_POST").
        if (parts.length == 1) {
            String only = parts[0].trim();
            if (Crypto.isValidAddress(only)) {
                return new QdnPattern(null, null, null, only, negated);
            }
            return new QdnPattern(segmentGlob(only), null, null, null, negated);
        }

        String serviceGlob = segmentGlob(parts[0]);
        String nameSegment = parts[1].trim();

        // The IDENTIFIER may itself contain '/', so re-join any remaining segments.
        String identifierGlob = null;
        if (parts.length >= 3) {
            StringBuilder identifier = new StringBuilder(parts[2]);
            for (int i = 3; i < parts.length; i++) {
                identifier.append('/').append(parts[i]);
            }
            identifierGlob = exactSegmentGlob(identifier.toString());
        }

        if (Crypto.isValidAddress(nameSegment)) {
            return new QdnPattern(serviceGlob, null, identifierGlob, nameSegment, negated);
        }
        return new QdnPattern(serviceGlob, segmentGlob(nameSegment), identifierGlob, null, negated);
    }

    /**
     * The address this pattern's NAME segment aliases, or {@code null} if it targets a name/glob.
     */
    public String getAddressAlias() {
        return this.addressAlias;
    }

    /**
     * Whether this is a {@code !} negation (exception) entry.
     */
    public boolean isNegated() {
        return this.negated;
    }

    /**
     * Return a copy of this pattern with its NAME segment bound to a concrete name (preserving the
     * negation flag). Used by {@link QdnFilter} to expand an address alias into one pattern per
     * owned name.
     */
    public QdnPattern withName(String name) {
        return new QdnPattern(this.serviceGlob, name, this.identifierGlob, null, this.negated);
    }

    /**
     * Whether this pattern matches the given resource triple. Address-alias patterns never match
     * directly (they must first be expanded via {@link #withName(String)}). The negation flag is
     * <i>not</i> considered here &mdash; it is applied by {@link QdnFilter} when combining patterns.
     */
    public boolean matches(String serviceName, String name, String identifier) {
        if (this.addressAlias != null) {
            return false;
        }
        return globMatch(this.serviceGlob, serviceName, false)
                && globMatch(this.nameGlob, name, false)
                && globMatch(this.identifierGlob, identifier, true);
    }

    /**
     * Normalise a raw segment to a glob, treating a blank segment or a lone {@code *} as
     * "match anything" ({@code null}).
     */
    private static String segmentGlob(String segment) {
        if (segment == null) {
            return null;
        }
        String trimmed = segment.trim();
        if (trimmed.isEmpty() || trimmed.equals("*")) {
            return null;
        }
        return trimmed;
    }

    /**
     * Like {@link #segmentGlob} but without trimming surrounding whitespace, since the IDENTIFIER
     * segment is matched byte-exactly (case-sensitively). A blank segment or a lone {@code *} still
     * means "match anything" ({@code null}).
     */
    private static String exactSegmentGlob(String segment) {
        if (segment == null) {
            return null;
        }
        if (segment.isEmpty() || segment.equals("*")) {
            return null;
        }
        return segment;
    }

    private static boolean globMatch(String glob, String text, boolean caseSensitive) {
        if (glob == null) {
            return true;
        }
        String t = text == null ? "" : text;
        String g = glob;
        if (!caseSensitive) {
            t = t.toLowerCase(Locale.ROOT);
            g = g.toLowerCase(Locale.ROOT);
        }
        return wildcard(g, t);
    }

    /**
     * Iterative {@code *}-wildcard match (any other character is matched literally).
     */
    private static boolean wildcard(String pattern, String text) {
        int p = 0, t = 0, star = -1, mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (star != -1) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
