package org.qortium.test.apps;

import org.qortium.api.ApiError;
import org.qortium.globalization.Translator;
import org.qortium.transaction.Transaction.ValidationResult;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CheckTranslations {

	private static final Set<String> BUNDLE_CLASS_NAMES = Set.of("ApiError", "SysTray", "TransactionValidity");
	private static final Pattern BUNDLE_FILENAME_PATTERN = Pattern.compile("^(" + String.join("|", BUNDLE_CLASS_NAMES) + ")_(.+)\\.properties$");

	private static final Set<String> SYSTRAY_KEYS = Set.of("APPLYING_BOOTSTRAP_AND_RESTARTING", "APPLYING_RESTARTING_NODE",
			"APPLYING_UPDATE_AND_RESTARTING", "AUTO_UPDATE", "BLOCK_HEIGHT", "BLOCKS_REMAINING", "BOOTSTRAP_NODE",
			"BUILD_VERSION", "CHECK_TIME_ACCURACY", "CONNECTING", "CONNECTION", "CONNECTIONS", "CREATING_BACKUP_OF_DB_FILES",
			"DB_BACKUP", "DB_CHECKPOINT", "DB_MAINTENANCE", "EXIT", "LITE_NODE", "MINTING_DISABLED", "MINTING_ENABLED",
			"PERFORMING_DB_CHECKPOINT", "PERFORMING_DB_MAINTENANCE", "RESTARTING_NODE", "SYNCHRONIZE_CLOCK",
			"SYNCHRONIZING_BLOCKCHAIN", "SYNCHRONIZING_CLOCK");

	public static void main(String[] args) throws IOException, URISyntaxException {
		int issueCount = 0;

		for (String lang : discoverSupportedLangs()) {
			System.out.println(String.format("\n# Checking '%s' translations", lang));

			Locale.setDefault(localeForLang(lang));

			issueCount += checkTranslations("TransactionValidity", lang, Arrays.stream(ValidationResult.values()).map(value -> value.name()).collect(Collectors.toSet()));
			issueCount += checkTranslations("ApiError", lang, Arrays.stream(ApiError.values()).map(value -> value.name()).collect(Collectors.toSet()));

			issueCount += checkTranslations("SysTray", lang, SYSTRAY_KEYS);
		}

		if (issueCount == 0)
			System.out.println("\nAll translation bundles are in sync.");
		else
			System.out.println(String.format("\nFound %,d translation issue%s.", issueCount, issueCount == 1 ? "" : "s"));
	}

	private static List<String> discoverSupportedLangs() throws IOException, URISyntaxException {
		Set<String> langs = new TreeSet<>();

		scanI18nDirectory(Paths.get("src/main/resources/i18n"), langs);

		Enumeration<URL> resourceUrls = CheckTranslations.class.getClassLoader().getResources("i18n");
		while (resourceUrls.hasMoreElements()) {
			URL resourceUrl = resourceUrls.nextElement();

			if ("file".equals(resourceUrl.getProtocol()))
				scanI18nDirectory(Paths.get(resourceUrl.toURI()), langs);
		}

		if (langs.isEmpty())
			throw new IllegalStateException("No translation bundles found");

		List<String> sortedLangs = new ArrayList<>(langs);
		if (sortedLangs.remove("en"))
			sortedLangs.add(0, "en");

		return sortedLangs;
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

	private static Locale localeForLang(String lang) {
		return Locale.forLanguageTag(lang.replace('_', '-'));
	}

	private static int checkTranslations(String className, String lang, Set<String> keys) {
		System.out.println(String.format("## Checking '%s' translations for %s", lang, className));

		int issueCount = 0;
		Set<String> allKeys = Translator.INSTANCE.keySet(className, lang);
		if (allKeys == null) {
			System.out.println(String.format("NO '%s' translations for %s!", lang, className));
			++issueCount;
			allKeys = Collections.emptySet();
		}

		allKeys = new HashSet<>(allKeys);

		for (String key : keys) {
			if (!allKeys.remove(key)) {
				System.out.println(String.format("Missing key '%s' in %s_%s.properties", key, className, lang));
				++issueCount;
			}
		}

		// Any leftover keys?
		for (String key : allKeys) {
			System.out.println(String.format("Extraneous key '%s' in %s_%s.properties", key, className, lang));
			++issueCount;
		}

		return issueCount;
	}

}
