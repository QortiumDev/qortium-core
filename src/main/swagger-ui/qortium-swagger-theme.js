(function () {
  const root = document.documentElement;
  const textScales = {
    "extra-small": "0.88",
    small: "0.94",
    medium: "1",
    large: "1.3",
    "extra-large": "1.7",
    huge: "2.1",
  };
  const accents = new Set([
    "blue",
    "cyan",
    "green",
    "orange",
    "pink",
    "purple",
    "red",
    "teal",
    "yellow",
  ]);

  function getQuerySetting(name) {
    const value = new URLSearchParams(window.location.search).get(name);
    return value ? value.trim() : "";
  }

  function applyTheme(theme) {
    const resolvedTheme = theme === "dark" || theme === "light"
      ? theme
      : window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";

    root.dataset.theme = resolvedTheme;
    root.classList.toggle("dark-mode", resolvedTheme === "dark");
    root.style.colorScheme = resolvedTheme;
  }

  function applyAccent(accent) {
    if (!accents.has(accent)) {
      accent = "green";
    }

    root.dataset.accent = accent;
  }

  function applyTextSize(textSize) {
    const scale = textScales[textSize] || textScales.medium;
    root.dataset.textSize = textScales[textSize] ? textSize : "medium";
    root.style.setProperty("--text-scale", scale);
  }

  function applyDisplaySettings(settings) {
    if (Object.prototype.hasOwnProperty.call(settings, "theme")) {
      applyTheme(settings.theme);
    }

    if (Object.prototype.hasOwnProperty.call(settings, "accent")) {
      applyAccent(settings.accent);
    }

    if (Object.prototype.hasOwnProperty.call(settings, "textSize")) {
      applyTextSize(settings.textSize);
    }
  }

  applyDisplaySettings({
    theme: getQuerySetting("theme"),
    accent: getQuerySetting("accent"),
    textSize: getQuerySetting("textSize") || getQuerySetting("text-size"),
  });

  window.addEventListener("message", function (event) {
    const data = event.data;

    if (!data || typeof data !== "object") {
      return;
    }

    if (data.action === "THEME_CHANGED") {
      applyTheme(data.theme);
    } else if (data.action === "ACCENT_CHANGED") {
      applyAccent(data.accent);
    } else if (data.action === "TEXT_SIZE_CHANGED") {
      applyTextSize(data.textSize);
    } else if (data.action === "DISPLAY_SETTINGS_CHANGED") {
      applyDisplaySettings({
        theme: data.theme,
        accent: data.accent,
        textSize: data.textSize,
      });
    }
  });
}());
