"use strict";

const t = (en, es) => (document.documentElement.lang === "es" ? es : en);

const menuButton = document.querySelector(".menu-toggle");
const navigation = document.querySelector("#navigation");
function closeMenu() {
  navigation.classList.remove("open");
  menuButton.setAttribute("aria-expanded", "false");
  menuButton.setAttribute("aria-label", t("Open menu", "Abrir menú"));
}
menuButton.addEventListener("click", () => {
  const open = menuButton.getAttribute("aria-expanded") !== "true";
  menuButton.setAttribute("aria-expanded", String(open));
  menuButton.setAttribute(
    "aria-label",
    open ? t("Close menu", "Cerrar menú") : t("Open menu", "Abrir menú"),
  );
  navigation.classList.toggle("open", open);
});
navigation
  .querySelectorAll("a")
  .forEach((link) => link.addEventListener("click", closeMenu));
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeMenu();
});
window.matchMedia("(min-width: 1101px)").addEventListener("change", closeMenu);

const engineSelect = document.querySelector('select[name="engine"]');
const configurationSelect = document.querySelector("#configuration");
function syncEngineStages() {
  const customEngine =
    engineSelect.value !== "" && engineSelect.value !== "230 Kompressor";
  [...configurationSelect.options].forEach((option) => {
    option.disabled = customEngine && option.value !== "custom";
  });
  if (customEngine) configurationSelect.value = "custom";
}
engineSelect.addEventListener("change", syncEngineStages);
document.querySelectorAll("[data-stage]").forEach((link) =>
  link.addEventListener("click", () => {
    if (link.dataset.stage !== "custom") engineSelect.value = "230 Kompressor";
    syncEngineStages();
    configurationSelect.value = link.dataset.stage;
  }),
);
syncEngineStages();

const imageDialog = document.querySelector("#image-dialog");
let captureTrigger;
document.querySelectorAll("[data-lightbox]").forEach((button) =>
  button.addEventListener("click", () => {
    captureTrigger = button;
    const image = document.querySelector("#dialog-image");
    image.src = button.dataset.lightbox;
    image.alt = button.querySelector("img")?.alt || button.dataset.caption;
    document.querySelector("#dialog-caption").textContent =
      button.dataset.caption;
    imageDialog.showModal();
  }),
);
document
  .querySelector(".dialog-close")
  .addEventListener("click", () => imageDialog.close());
imageDialog.addEventListener("click", (event) => {
  const bounds = imageDialog.getBoundingClientRect();
  if (
    event.target === imageDialog &&
    (event.clientX < bounds.left ||
      event.clientX > bounds.right ||
      event.clientY < bounds.top ||
      event.clientY > bounds.bottom)
  )
    imageDialog.close();
});
imageDialog.addEventListener("close", () => captureTrigger?.focus());

const form = document.querySelector("#consultation-form");
const formStatus = document.querySelector("#form-status");
let submitting = false;
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (submitting || !form.reportValidity()) return;
  submitting = true;
  const button = form.querySelector('button[type="submit"]');
  const originalLabel = button.innerHTML;
  button.disabled = true;
  button.textContent = t("Sending…", "Enviando…");
  form.setAttribute("aria-busy", "true");
  formStatus.textContent = t("Sending your enquiry…", "Enviando tu consulta…");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);
  try {
    const tokenResponse = await fetch(form.action, {
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });
    const session = await tokenResponse.json();
    if (!tokenResponse.ok || !session.token)
      throw new Error(
        session.message ||
          t(
            "Please try again or email Daniel directly.",
            "Inténtalo de nuevo o escribe directamente a Daniel.",
          ),
      );
    const values = new FormData(form);
    values.set("token", session.token);
    const response = await fetch(form.action, {
      method: "POST",
      body: values,
      credentials: "same-origin",
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });
    const result = await response.json();
    if (!response.ok || result.ok !== true)
      throw new Error(
        result.message ||
          t(
            "Your enquiry could not be sent.",
            "No se ha podido enviar tu consulta.",
          ),
      );
    formStatus.textContent = result.message;
    form.reset();
    syncEngineStages();
  } catch (error) {
    formStatus.textContent =
      error.name === "AbortError"
        ? t(
            "The request timed out. Delivery could not be confirmed. Please email daniel@drasite.com before retrying.",
            "Se ha agotado el tiempo de espera. No se ha podido confirmar el envío. Escribe a daniel@drasite.com antes de volver a intentarlo.",
          )
        : error instanceof SyntaxError || error instanceof TypeError
          ? t(
              "Could not connect to the mail service. Please email daniel@drasite.com directly.",
              "No se ha podido conectar con el servicio de correo. Escribe directamente a daniel@drasite.com.",
            )
          : error.message;
  } finally {
    clearTimeout(timer);
    button.disabled = false;
    button.innerHTML = originalLabel;
    form.removeAttribute("aria-busy");
    submitting = false;
  }
});
let toastTimer;
document.querySelectorAll("[data-download]").forEach((link) =>
  link.addEventListener("click", () => {
    const toast = document.querySelector("#toast");
    toast.textContent = t(
      `Opening ${link.dataset.download} from the official project repository.`,
      `Abriendo ${link.dataset.download} desde el repositorio oficial del proyecto.`,
    );
    toast.classList.add("visible");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove("visible"), 5000);
  }),
);
