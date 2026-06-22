package org.qortium.globalization;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.settings.Settings;

import java.util.*;

public enum Translator {
	INSTANCE;

	private static final Logger LOGGER = LogManager.getLogger(Translator.class);

	private static final Map<String, ResourceBundle> resourceBundles = new HashMap<>();

	public String translate(String className, String lang, String key, Object... args) {
		ResourceBundle resourceBundle = getOrLoadResourceBundle(className, lang);

		if (resourceBundle == null || !resourceBundle.containsKey(key))
			return "!!" + lang + ":" + className + "." + key + "!!";

		String template = resourceBundle.getString(key);
		try {
			return String.format(template, args);
		} catch (MissingFormatArgumentException e) {
			return template;
		}
	}

	public String translate(String className, String key) {
		return this.translate(className, Settings.getInstance().getLocaleLang(), key);
	}

	public Set<String> keySet(String className, String lang) {
		ResourceBundle resourceBundle = getOrLoadResourceBundle(className, lang);

		if (resourceBundle == null)
			return null;

		return resourceBundle.keySet();
	}

	private synchronized ResourceBundle getOrLoadResourceBundle(String className, String lang) {
		String bundleKey = className + ":" + lang;

		ResourceBundle resourceBundle = resourceBundles.get(bundleKey);
		if (resourceBundle != null || resourceBundles.containsKey(bundleKey))
			return resourceBundle;

		try {
			// No-fallback control stops lookup falling back to the JVM's default locale,
			// so an unsupported language deterministically resolves to the English root bundle.
			resourceBundle = ResourceBundle.getBundle("i18n." + className, localeForLang(lang),
					ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
		} catch (MissingResourceException e) {
			LOGGER.warn(String.format("Can't locate '%s' translation resource bundle for %s", lang, className));
			// Set to null then fall-through to storing in map so we don't emit warning more than once
			resourceBundle = null;
		}

		resourceBundles.put(bundleKey, resourceBundle);

		return resourceBundle;
	}

	private static Locale localeForLang(String lang) {
		Locale locale = Locale.forLanguageTag(lang.replace('_', '-'));
		return locale.getLanguage().equals("no") ? Locale.forLanguageTag("nb") : locale;
	}

}
