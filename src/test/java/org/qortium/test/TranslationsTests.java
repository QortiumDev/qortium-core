package org.qortium.test;

import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.transaction.Transaction.ValidationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Keeps the i18n resource bundles in sync with the keys the code can ask for.
 *
 * Replaces the old manual {@code CheckTranslations} app so CI fails when a
 * language is missing a key, carries a stale key, or the root English
 * fallback bundles drift from the {@code _en} bundles.
 */
public class TranslationsTests {

	private static final Set<String> BUNDLE_CLASS_NAMES = Set.of("ApiError", "SysTray", "TransactionValidity");
	private static final Pattern BUNDLE_FILENAME_PATTERN = Pattern.compile("^(" + String.join("|", BUNDLE_CLASS_NAMES) + ")_(.+)\\.properties$");

	private static final Path I18N_SOURCE_PATH = Paths.get("src/main/resources/i18n");

	// SysTray keys are referenced as string literals in code, so they are listed here
	private static final Set<String> SYSTRAY_KEYS = Set.of("APPLYING_BOOTSTRAP_AND_RESTARTING", "APPLYING_RESTARTING_NODE",
			"APPLYING_UPDATE_AND_RESTARTING", "AUTO_UPDATE", "BLOCK_HEIGHT", "BLOCKS_REMAINING", "BOOTSTRAP",
			"BOOTSTRAP_CONFIRM", "BOOTSTRAP_NODE",
			"BUILD_VERSION", "CHECK_FOR_UPDATE", "CHECK_TIME_ACCURACY", "CONNECTING", "CONNECTION", "CONNECTIONS",
			"CREATING_BACKUP_OF_DB_FILES",
			"DB_BACKUP", "DB_CHECKPOINT", "DB_MAINTENANCE", "EXIT", "INSTALL_UPDATE", "LITE_NODE", "MINTING_DISABLED", "MINTING_ENABLED",
			"PERFORMING_DB_CHECKPOINT", "PERFORMING_DB_MAINTENANCE", "RESTART", "RESTART_CONFIRM", "RESTARTING_NODE", "SYNCHRONIZE_CLOCK",
			"SYNCHRONIZING_BLOCKCHAIN", "SYNCHRONIZING_CLOCK", "UPDATE_AVAILABLE_PROMPT", "UPDATE_CHECK_FAILED",
			"UP_TO_DATE");

	@Test
	public void testEveryLanguageHasExactlyTheExpectedKeys() throws IOException, URISyntaxException {
		Set<String> apiErrorKeys = enumNames(ApiError.values());
		Set<String> validationKeys = enumNames(ValidationResult.values());

		List<String> langs = discoverSupportedLangs();
		assertTrue("No translation languages discovered", !langs.isEmpty());
		assertTrue("English bundles not discovered", langs.contains("en"));

		List<String> issues = new ArrayList<>();
		for (String lang : langs) {
			checkBundleKeys("ApiError", lang, apiErrorKeys, issues);
			checkBundleKeys("TransactionValidity", lang, validationKeys, issues);
			checkBundleKeys("SysTray", lang, SYSTRAY_KEYS, issues);
		}

		assertTrue(describe(issues), issues.isEmpty());
	}

	@Test
	public void testRootFallbackBundlesMatchEnglish() throws IOException {
		List<String> issues = new ArrayList<>();

		for (String className : new TreeSet<>(BUNDLE_CLASS_NAMES)) {
			Properties rootBundle = loadProperties(className + ".properties");
			Properties englishBundle = loadProperties(className + "_en.properties");

			if (rootBundle == null) {
				issues.add(String.format("Missing root fallback bundle %s.properties", className));
				continue;
			}
			if (englishBundle == null) {
				issues.add(String.format("Missing English bundle %s_en.properties", className));
				continue;
			}

			for (String key : englishBundle.stringPropertyNames()) {
				String rootValue = rootBundle.getProperty(key);
				if (rootValue == null)
					issues.add(String.format("Missing key '%s' in root bundle %s.properties", key, className));
				else if (!rootValue.equals(englishBundle.getProperty(key)))
					issues.add(String.format("Key '%s' in root bundle %s.properties differs from %s_en.properties", key, className, className));
			}

			for (String key : rootBundle.stringPropertyNames())
				if (englishBundle.getProperty(key) == null)
					issues.add(String.format("Extraneous key '%s' in root bundle %s.properties", key, className));
		}

		assertTrue(describe(issues), issues.isEmpty());
	}

	private static void checkBundleKeys(String className, String lang, Set<String> expectedKeys, List<String> issues) throws IOException {
		String fileName = className + "_" + lang + ".properties";

		Properties bundle = loadProperties(fileName);
		if (bundle == null) {
			issues.add(String.format("No '%s' translations for %s", lang, className));
			return;
		}

		Set<String> bundleKeys = new HashSet<>(bundle.stringPropertyNames());

		for (String key : expectedKeys)
			if (!bundleKeys.remove(key))
				issues.add(String.format("Missing key '%s' in %s", key, fileName));

		// Any leftover keys?
		for (String key : bundleKeys)
			issues.add(String.format("Extraneous key '%s' in %s", key, fileName));
	}

	private static List<String> discoverSupportedLangs() throws IOException, URISyntaxException {
		Set<String> langs = new TreeSet<>();

		// The source tree is authoritative; only fall back to the classpath when it
		// is unavailable, as compiled output can hold stale copies of renamed bundles.
		scanI18nDirectory(I18N_SOURCE_PATH, langs);

		if (langs.isEmpty()) {
			Enumeration<URL> resourceUrls = TranslationsTests.class.getClassLoader().getResources("i18n");
			while (resourceUrls.hasMoreElements()) {
				URL resourceUrl = resourceUrls.nextElement();

				if ("file".equals(resourceUrl.getProtocol()))
					scanI18nDirectory(Paths.get(resourceUrl.toURI()), langs);
			}
		}

		return new ArrayList<>(langs);
	}

	private static void scanI18nDirectory(Path i18nPath, Set<String> langs) throws IOException {
		if (!Files.isDirectory(i18nPath))
			return;

		try (Stream<Path> paths = Files.list(i18nPath)) {
			for (Path path : paths.collect(Collectors.toList())) {
				Matcher matcher = BUNDLE_FILENAME_PATTERN.matcher(path.getFileName().toString());

				if (matcher.matches())
					langs.add(matcher.group(2));
			}
		}
	}

	/** Loads a bundle from the source tree if present (so tests see uncompiled edits), else from the classpath. */
	private static Properties loadProperties(String fileName) throws IOException {
		Path sourcePath = I18N_SOURCE_PATH.resolve(fileName);
		if (Files.isRegularFile(sourcePath))
			try (Reader reader = Files.newBufferedReader(sourcePath, StandardCharsets.UTF_8)) {
				return loadProperties(reader);
			}

		InputStream inputStream = TranslationsTests.class.getResourceAsStream("/i18n/" + fileName);
		if (inputStream == null)
			return null;

		try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
			return loadProperties(reader);
		}
	}

	private static Properties loadProperties(Reader reader) throws IOException {
		Properties properties = new Properties();
		properties.load(reader);
		return properties;
	}

	private static Set<String> enumNames(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
	}

	private static String describe(List<String> issues) {
		return String.format("Found %d translation issue%s:%n%s",
				issues.size(), issues.size() == 1 ? "" : "s", String.join(System.lineSeparator(), issues));
	}

}
