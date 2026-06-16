package org.qortium.gui;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

enum SplashTheme {

	DARK(Color.BLACK, Color.WHITE, "app/qortium-app-500.png"),
	LIGHT(Color.WHITE, Color.BLACK, "app/qortium-app-500-light.png");

	static final String PROPERTY_NAME = "qortium.splash.theme";

	private static final long COMMAND_TIMEOUT_MILLIS = 1000L;

	private final Color backgroundColor;
	private final Color foregroundColor;
	private final String splashImageResource;

	SplashTheme(Color backgroundColor, Color foregroundColor, String splashImageResource) {
		this.backgroundColor = backgroundColor;
		this.foregroundColor = foregroundColor;
		this.splashImageResource = splashImageResource;
	}

	Color getBackgroundColor() {
		return this.backgroundColor;
	}

	Color getForegroundColor() {
		return this.foregroundColor;
	}

	String getSplashImageResource() {
		return this.splashImageResource;
	}

	static SplashTheme detect() {
		return detect(System.getProperty(PROPERTY_NAME), System.getProperty("os.name", ""), System::getenv, SplashTheme::runCommand);
	}

	static SplashTheme detect(String configuredTheme, String osName, Function<String, String> environment,
			CommandRunner commandRunner) {
		String normalizedTheme = normalize(configuredTheme);

		if ("light".equals(normalizedTheme))
			return LIGHT;

		if ("dark".equals(normalizedTheme))
			return DARK;

		if (!normalizedTheme.isEmpty() && !"system".equals(normalizedTheme))
			return DARK;

		String normalizedOsName = normalize(osName);

		if (normalizedOsName.contains("mac"))
			return detectMacTheme(commandRunner).orElse(DARK);

		if (normalizedOsName.contains("win"))
			return detectWindowsTheme(commandRunner).orElse(DARK);

		if (normalizedOsName.contains("linux"))
			return detectLinuxTheme(environment, commandRunner).orElse(DARK);

		return DARK;
	}

	private static Optional<SplashTheme> detectMacTheme(CommandRunner commandRunner) {
		return commandRunner.run("defaults", "read", "-g", "AppleInterfaceStyle").flatMap(SplashTheme::parseMacTheme);
	}

	private static Optional<SplashTheme> detectWindowsTheme(CommandRunner commandRunner) {
		Optional<CommandResult> result = commandRunner.run("reg", "query",
				"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
				"/v", "AppsUseLightTheme");

		if (!result.isPresent() || result.get().exitCode != 0)
			return Optional.empty();

		return parseWindowsTheme(result.get().output);
	}

	private static Optional<SplashTheme> detectLinuxTheme(Function<String, String> environment, CommandRunner commandRunner) {
		Optional<SplashTheme> environmentTheme = parseThemeName(environment.apply("GTK_THEME"));
		if (environmentTheme.isPresent())
			return environmentTheme;

		Optional<SplashTheme> gnomeColorScheme = runAndParse(commandRunner, SplashTheme::parseLinuxColorScheme,
				"gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
		if (gnomeColorScheme.isPresent())
			return gnomeColorScheme;

		Optional<SplashTheme> gtkTheme = runAndParse(commandRunner, SplashTheme::parseThemeName,
				"gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
		if (gtkTheme.isPresent())
			return gtkTheme;

		Optional<SplashTheme> kde6Theme = runAndParse(commandRunner, SplashTheme::parseThemeName,
				"kreadconfig6", "--group", "General", "--key", "ColorScheme");
		if (kde6Theme.isPresent())
			return kde6Theme;

		return runAndParse(commandRunner, SplashTheme::parseThemeName,
				"kreadconfig5", "--group", "General", "--key", "ColorScheme");
	}

	private static Optional<SplashTheme> runAndParse(CommandRunner commandRunner,
			Function<String, Optional<SplashTheme>> parser, String... command) {
		Optional<CommandResult> result = commandRunner.run(command);

		if (!result.isPresent() || result.get().exitCode != 0)
			return Optional.empty();

		return parser.apply(result.get().output);
	}

	static Optional<SplashTheme> parseMacTheme(CommandResult result) {
		if (result == null)
			return Optional.empty();

		String output = normalize(result.output);

		if (output.contains("dark"))
			return Optional.of(DARK);

		if (result.exitCode != 0 || !output.isEmpty())
			return Optional.of(LIGHT);

		return Optional.empty();
	}

	static Optional<SplashTheme> parseWindowsTheme(String output) {
		if (output == null)
			return Optional.empty();

		for (String line : output.split("\\R")) {
			String normalizedLine = normalize(line);

			if (!normalizedLine.contains("appsuselighttheme"))
				continue;

			String[] tokens = normalizedLine.split("\\s+");
			String value = tokens[tokens.length - 1];

			if ("0".equals(value) || "0x0".equals(value) || "0x00000000".equals(value))
				return Optional.of(DARK);

			if ("1".equals(value) || "0x1".equals(value) || "0x00000001".equals(value))
				return Optional.of(LIGHT);
		}

		return Optional.empty();
	}

	static Optional<SplashTheme> parseLinuxColorScheme(String output) {
		String normalizedOutput = normalize(output);

		if (normalizedOutput.contains("prefer-dark") || "dark".equals(normalizedOutput))
			return Optional.of(DARK);

		if (normalizedOutput.contains("prefer-light") || "light".equals(normalizedOutput))
			return Optional.of(LIGHT);

		return Optional.empty();
	}

	static Optional<SplashTheme> parseThemeName(String output) {
		String normalizedOutput = normalize(output);

		if (normalizedOutput.isEmpty())
			return Optional.empty();

		if (normalizedOutput.contains("dark"))
			return Optional.of(DARK);

		return Optional.of(LIGHT);
	}

	private static String normalize(String value) {
		if (value == null)
			return "";

		return value.trim()
				.replace("'", "")
				.replace("\"", "")
				.toLowerCase(Locale.ROOT);
	}

	private static Optional<CommandResult> runCommand(String... command) {
		Process process = null;

		try {
			process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();

			if (!process.waitFor(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				return Optional.empty();
			}

			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return Optional.of(new CommandResult(process.exitValue(), output));
		} catch (IOException e) {
			return Optional.empty();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (process != null)
				process.destroyForcibly();

			return Optional.empty();
		}
	}

	interface CommandRunner {
		Optional<CommandResult> run(String... command);
	}

	static final class CommandResult {
		final int exitCode;
		final String output;

		CommandResult(int exitCode, String output) {
			this.exitCode = exitCode;
			this.output = output;
		}
	}

}
