package org.qortium.gui;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SplashThemeTests {

	@Test
	public void testManualThemeOverrides() {
		assertEquals(SplashTheme.LIGHT, SplashTheme.detect("light", "Linux", noEnvironment(), noCommands()));
		assertEquals(SplashTheme.DARK, SplashTheme.detect("dark", "Linux", noEnvironment(), noCommands()));
		assertEquals(SplashTheme.DARK, SplashTheme.detect("unknown", "Linux", noEnvironment(), noCommands()));
	}

	@Test
	public void testDetectionDefaultsToDark() {
		assertEquals(SplashTheme.DARK, SplashTheme.detect(null, "Linux", noEnvironment(), noCommands()));
		assertEquals(SplashTheme.DARK, SplashTheme.detect("system", "Unknown OS", noEnvironment(), noCommands()));
	}

	@Test
	public void testMacThemeParsing() {
		assertEquals(SplashTheme.DARK, SplashTheme.parseMacTheme(new SplashTheme.CommandResult(0, "Dark")).get());
		assertEquals(SplashTheme.LIGHT, SplashTheme.parseMacTheme(new SplashTheme.CommandResult(1, "does not exist")).get());
		assertFalse(SplashTheme.parseMacTheme(new SplashTheme.CommandResult(0, "")).isPresent());
	}

	@Test
	public void testWindowsThemeParsing() {
		String darkOutput = "AppsUseLightTheme    REG_DWORD    0x0";
		String lightOutput = "AppsUseLightTheme    REG_DWORD    0x00000001";

		assertEquals(SplashTheme.DARK, SplashTheme.parseWindowsTheme(darkOutput).get());
		assertEquals(SplashTheme.LIGHT, SplashTheme.parseWindowsTheme(lightOutput).get());
		assertFalse(SplashTheme.parseWindowsTheme("OtherValue    REG_DWORD    0x1").isPresent());
	}

	@Test
	public void testLinuxThemeParsing() {
		assertEquals(SplashTheme.DARK, SplashTheme.parseLinuxColorScheme("'prefer-dark'").get());
		assertEquals(SplashTheme.LIGHT, SplashTheme.parseLinuxColorScheme("'prefer-light'").get());
		assertFalse(SplashTheme.parseLinuxColorScheme("'default'").isPresent());
		assertEquals(SplashTheme.DARK, SplashTheme.parseThemeName("Adwaita-dark").get());
		assertEquals(SplashTheme.LIGHT, SplashTheme.parseThemeName("Adwaita").get());
	}

	@Test
	public void testLinuxEnvironmentTheme() {
		assertEquals(SplashTheme.DARK, SplashTheme.detect("system", "Linux", key -> "Adwaita:dark", noCommands()));
		assertEquals(SplashTheme.LIGHT, SplashTheme.detect("system", "Linux", key -> "Adwaita", noCommands()));
	}

	@Test
	public void testSystemThemeDetection() {
		assertEquals(SplashTheme.DARK, SplashTheme.detect("system", "Mac OS X", noEnvironment(),
				commands("defaults read -g AppleInterfaceStyle", new SplashTheme.CommandResult(0, "Dark"))));

		assertEquals(SplashTheme.LIGHT, SplashTheme.detect("system", "Windows 11", noEnvironment(),
				commands("reg query HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize /v AppsUseLightTheme",
						new SplashTheme.CommandResult(0, "AppsUseLightTheme    REG_DWORD    0x1"))));

		assertEquals(SplashTheme.LIGHT, SplashTheme.detect("system", "Linux", noEnvironment(),
				commands("gsettings get org.gnome.desktop.interface gtk-theme",
						new SplashTheme.CommandResult(0, "Adwaita"))));
	}

	private Function<String, String> noEnvironment() {
		return key -> null;
	}

	private SplashTheme.CommandRunner noCommands() {
		return command -> Optional.empty();
	}

	private SplashTheme.CommandRunner commands(String command, SplashTheme.CommandResult result) {
		Map<String, SplashTheme.CommandResult> results = new HashMap<>();
		results.put(command, result);

		return requestedCommand -> Optional.ofNullable(results.get(String.join(" ", requestedCommand)));
	}

}
