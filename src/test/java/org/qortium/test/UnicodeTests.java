package org.qortium.test;

import org.junit.Test;
import org.qortium.utils.Unicode;

import static org.junit.Assert.*;
import static org.qortium.utils.Unicode.BRAILLE_PATTERN_BLANK;
import static org.qortium.utils.Unicode.HALFWIDTH_HANGUL_FILLER;
import static org.qortium.utils.Unicode.HANGUL_CHOSEONG_FILLER;
import static org.qortium.utils.Unicode.HANGUL_FILLER;
import static org.qortium.utils.Unicode.HANGUL_JUNGSEONG_FILLER;
import static org.qortium.utils.Unicode.NO_BREAK_SPACE;
import static org.qortium.utils.Unicode.ZERO_WIDTH_SPACE;

public class UnicodeTests {

	@Test
	public void testWhitespace() {
		String input = "  " + NO_BREAK_SPACE + "test  ";

		String output = Unicode.normalize(input);

		assertEquals("trim & collapse failed", "test", output);
	}

	@Test
	public void testVisualBlankCharacters() {
		String[] visualBlanks = new String[] {
				BRAILLE_PATTERN_BLANK,
				HANGUL_CHOSEONG_FILLER,
				HANGUL_JUNGSEONG_FILLER,
				HANGUL_FILLER,
				HALFWIDTH_HANGUL_FILLER
		};

		for (String visualBlank : visualBlanks) {
			assertEquals("visual blank trim failed", "sample", Unicode.normalize(visualBlank + "sample" + visualBlank));
			assertEquals("visual blank collapse failed", "sample name", Unicode.normalize("sample" + visualBlank + visualBlank + "name"));
			assertEquals("strings should match", Unicode.sanitize("sample"), Unicode.sanitize(visualBlank + "sample" + visualBlank));
			assertEquals("strings should match", Unicode.sanitize("sample name"), Unicode.sanitize("sample" + visualBlank + "name"));
		}
	}

	@Test
	public void testOtherCodepointsAreRemovedFromNormalizedNames() {
		String[] unsafeCodepoints = new String[] {
				"\u0007", // bell control
				"\u202d", // left-to-right override
				"\u202e", // right-to-left override
				"\u2066", // left-to-right isolate
				"\u2069", // pop directional isolate
				"\ue000" // private use
		};

		for (String unsafeCodepoint : unsafeCodepoints) {
			assertEquals("unsafe codepoint strip failed", "sample", Unicode.normalize("sam" + unsafeCodepoint + "ple"));
			assertEquals("strings should match", Unicode.sanitize("sample"), Unicode.sanitize("sam" + unsafeCodepoint + "ple"));
		}
	}

	@Test
	public void testCaseComparison() {
		String input1 = "  " + NO_BREAK_SPACE + "test  ";
		String input2 = "  " + NO_BREAK_SPACE + "TEST  " + ZERO_WIDTH_SPACE;

		assertEquals("strings should match", Unicode.sanitize(input1), Unicode.sanitize(input2));
	}

	@Test
	public void testHomoglyph() {
		String omicron = "\u03bf";

		String input1 = "  " + NO_BREAK_SPACE + "toÁst  ";
		String input2 = "  " + NO_BREAK_SPACE + "t" + omicron + "ast  " + ZERO_WIDTH_SPACE;

		assertEquals("strings should match", Unicode.sanitize(input1), Unicode.sanitize(input2));
	}

	@Test
	public void testCaseFoldedIHomoglyphs() {
		assertEquals("1abe1", Unicode.sanitize("label"));
		assertEquals("strings should match", Unicode.sanitize("label"), Unicode.sanitize("1abel"));
		assertEquals("strings should match", Unicode.sanitize("label"), Unicode.sanitize("Iabel"));

		assertEquals("11near", Unicode.sanitize("linear"));
		assertEquals("strings should match", Unicode.sanitize("linear"), Unicode.sanitize("1inear"));
		assertEquals("strings should match", Unicode.sanitize("linear"), Unicode.sanitize("Iinear"));

		assertEquals("strings should match", Unicode.sanitize("Ian"), Unicode.sanitize("ian"));
		assertEquals("strings should match", Unicode.sanitize("Ian"), Unicode.sanitize("lan"));
		assertEquals("strings should match", Unicode.sanitize("Ian"), Unicode.sanitize("1an"));
	}

	@Test
	public void testEmojis() {
		/*
		 * Emojis shouldn't reduce down to empty strings.
		 *
		 * 🥳 Face with Party Horn and Party Hat Emoji U+1F973
		 */
		String emojis = "\uD83E\uDD73";

		assertFalse(Unicode.sanitize(emojis).isBlank());
	}

	@Test
	public void testSanitize() {
		/*
		 * Check various code points that should be stripped out when sanitizing / reducing
		 */
		String enclosingCombiningMark = "\u1100\u1161\u20DD"; // \u20DD is an enclosing combining mark and should be removed
		String spacingMark = "\u0A39\u0A3f"; // \u0A3f is spacing combining mark and should be removed
		String nonspacingMark = "c\u0302"; // \u0302 is a non-spacing combining mark and should be removed

		assertNotSame(enclosingCombiningMark, Unicode.sanitize(enclosingCombiningMark));
		assertNotSame(spacingMark, Unicode.sanitize(spacingMark));
		assertNotSame(nonspacingMark, Unicode.sanitize(nonspacingMark));

		String control = "\u001B\u009E"; // \u001B and \u009E are a control codes
		String format = "\u202A\u2062"; // \u202A and \u2062 are zero-width formatting codes
		String surrogate = "\uD800\uDFFF"; // surrogates
		String privateUse = "\uE1E0"; // \uE000 - \uF8FF is private use area
		String unassigned = "\uFAFA"; // \uFAFA is currently unassigned

		assertTrue(Unicode.sanitize(control).isBlank());
		assertTrue(Unicode.sanitize(format).isBlank());
		assertTrue(Unicode.sanitize(surrogate).isBlank());
		assertTrue(Unicode.sanitize(privateUse).isBlank());
		assertTrue(Unicode.sanitize(unassigned).isBlank());
	}
}
