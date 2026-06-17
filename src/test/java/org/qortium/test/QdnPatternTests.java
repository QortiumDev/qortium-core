package org.qortium.test;

import org.junit.Test;
import org.qortium.list.QdnFilter;
import org.qortium.list.QdnPattern;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the gitignore-style {@code followedQdn} / {@code blockedQdn} wildcard matcher.
 * Address-alias resolution is exercised separately (it needs a repository); these cover the pure
 * SERVICE/NAME/IDENTIFIER glob behaviour against the documented examples.
 */
public class QdnPatternTests {

    private static boolean matches(String pattern, String service, String name, String identifier) {
        QdnPattern parsed = QdnPattern.parse(pattern);
        assertNotNull("pattern should parse: " + pattern, parsed);
        return parsed.matches(service, name, identifier);
    }

    @Test
    public void testServiceOnly() {
        // "BLOG_POST" blocks/follows all blog posts (any name, any identifier, including none)
        assertTrue(matches("BLOG_POST", "BLOG_POST", "alice", "post1"));
        assertTrue(matches("BLOG_POST", "BLOG_POST", null, null));
        assertFalse(matches("BLOG_POST", "VIDEO", "alice", "post1"));
    }

    @Test
    public void testServiceCaseInsensitive() {
        assertTrue(matches("blog_post", "BLOG_POST", "alice", null));
    }

    @Test
    public void testServiceAndName() {
        // "APP/BOB" matches any app from BOB, regardless of identifier; names are case-insensitive
        assertTrue(matches("APP/BOB", "APP", "BOB", null));
        assertTrue(matches("APP/BOB", "APP", "bob", "anything"));
        assertFalse(matches("APP/BOB", "APP", "alice", null));
        assertFalse(matches("APP/BOB", "WEBSITE", "BOB", null));
    }

    @Test
    public void testIdentifierGlob() {
        // "VIDEO/*/cat*" matches any video whose identifier starts with cat
        assertTrue(matches("VIDEO/*/cat*", "VIDEO", "alice", "cat_video"));
        assertTrue(matches("VIDEO/*/cat*", "VIDEO", "bob", "cat"));
        assertFalse(matches("VIDEO/*/cat*", "VIDEO", "alice", "dog_video"));
        assertFalse(matches("VIDEO/*/cat*", "VIDEO", "alice", null));
    }

    @Test
    public void testAnyServiceForName() {
        // "*/TOM" matches all resources from TOM
        assertTrue(matches("*/TOM", "BLOG_POST", "tom", "x"));
        assertTrue(matches("*/TOM", "VIDEO", "TOM", null));
        assertFalse(matches("*/TOM", "VIDEO", "jerry", null));
    }

    @Test
    public void testIdentifierContains() {
        // "*/*/*fish*" matches any resource whose identifier contains fish
        assertTrue(matches("*/*/*fish*", "BLOG_POST", "alice", "swordfish"));
        assertTrue(matches("*/*/*fish*", "VIDEO", "bob", "fish"));
        assertFalse(matches("*/*/*fish*", "BLOG_POST", "alice", "cat"));
        assertFalse(matches("*/*/*fish*", "BLOG_POST", "alice", null));
    }

    @Test
    public void testIdentifierCaseSensitive() {
        assertTrue(matches("VIDEO/*/cat*", "VIDEO", "x", "cat1"));
        assertFalse(matches("VIDEO/*/cat*", "VIDEO", "x", "CAT1"));
    }

    @Test
    public void testIdentifierByteExact() {
        // The IDENTIFIER segment is byte-exact (not whitespace-trimmed like service/name),
        // so a leading space in the pattern only matches an identifier with that same space.
        assertTrue(matches("APP/BOB/ id", "APP", "BOB", " id"));
        assertFalse(matches("APP/BOB/ id", "APP", "BOB", "id"));
    }

    @Test
    public void testBlankParsesToNull() {
        assertNull(QdnPattern.parse(""));
        assertNull(QdnPattern.parse("   "));
        assertNull(QdnPattern.parse(null));
    }

    // --- negation (gitignore-style "!" exceptions, last match wins) ---

    private static boolean filterMatches(List<String> patterns, String service, String name, String identifier) {
        return QdnFilter.ofPatterns(patterns).matches(service, name, identifier);
    }

    @Test
    public void testNegationException() {
        // Block all VIDEO except BOB's videos
        List<String> patterns = List.of("VIDEO", "!VIDEO/BOB");
        assertTrue(filterMatches(patterns, "VIDEO", "alice", "x"));
        assertFalse(filterMatches(patterns, "VIDEO", "BOB", "x"));
        // A different service is unaffected (never matched a positive pattern)
        assertFalse(filterMatches(patterns, "BLOG_POST", "alice", null));
    }

    @Test
    public void testNegationLastMatchWins() {
        // Block all VIDEO, allow BOB, but re-block BOB's "secret*" videos
        List<String> patterns = List.of("VIDEO", "!VIDEO/BOB", "VIDEO/BOB/secret*");
        assertTrue(filterMatches(patterns, "VIDEO", "BOB", "secret1"));   // re-blocked by the last pattern
        assertFalse(filterMatches(patterns, "VIDEO", "BOB", "holiday"));  // still excepted
        assertTrue(filterMatches(patterns, "VIDEO", "alice", "secret1")); // alice was never excepted
    }

    @Test
    public void testNegationOnlyMatchesNothing() {
        assertFalse(filterMatches(List.of("!VIDEO"), "VIDEO", "alice", null));
    }

    @Test
    public void testEscapedLeadingBangIsLiteral() {
        // "\!APP/BOB" is a literal (non-negated) pattern targeting the service named "!APP",
        // which no real resource has — so it matches the literal but not real APP resources.
        assertTrue(filterMatches(List.of("\\!APP/BOB"), "!APP", "BOB", null));
        assertFalse(filterMatches(List.of("\\!APP/BOB"), "APP", "BOB", null));
    }
}
