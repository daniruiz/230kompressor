"use strict";

(() => {
  const url = new URL(window.location.href);
  const supported = (value) => value === "en" || value === "es";
  const explicit = url.searchParams.get("lang");
  const current = document.documentElement.lang;
  let saved;
  try {
    saved = localStorage.getItem("m111-language");
  } catch {
    /* Storage may be blocked. */
  }
  const browser = (navigator.languages || [navigator.language])
    .map((language) => language.toLowerCase().split("-")[0])
    .find(supported);
  // A direct Spanish URL is always Spanish; the landing page detects preferences.
  const language = supported(explicit)
    ? explicit
    : current === "es"
      ? "es"
      : supported(saved)
        ? saved
        : browser || "en";
  if (supported(explicit)) {
    try {
      localStorage.setItem("m111-language", explicit);
    } catch {
      /* URL still works. */
    }
  }
  if (language !== current) {
    const target = new URL(language === "es" ? "es.html" : "./", url);
    target.search = url.search;
    target.hash = url.hash;
    window.location.replace(target.href);
    return;
  }
  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-language]").forEach((link) => {
      link.addEventListener("click", () => {
        const target = new URL(
          link.dataset.language === "es" ? "es.html" : "./",
          url,
        );
        target.search = window.location.search;
        target.searchParams.set("lang", link.dataset.language);
        target.hash = window.location.hash;
        link.href = target.href;
        try {
          localStorage.setItem("m111-language", link.dataset.language);
        } catch {
          /* Optional. */
        }
      });
    });
  });
})();
