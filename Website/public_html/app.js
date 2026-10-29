'use strict';

const menuButton = document.querySelector('.menu-toggle');
const navigation = document.querySelector('#navigation');
function closeMenu() {
  navigation.classList.remove('open');
  menuButton.setAttribute('aria-expanded', 'false');
  menuButton.setAttribute('aria-label', 'Open menu');
}
menuButton.addEventListener('click', () => {
  const open = menuButton.getAttribute('aria-expanded') !== 'true';
  menuButton.setAttribute('aria-expanded', String(open));
  menuButton.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
  navigation.classList.toggle('open', open);
});
navigation.querySelectorAll('a').forEach(link => link.addEventListener('click', closeMenu));
document.addEventListener('keydown', event => { if (event.key === 'Escape') closeMenu(); });
window.matchMedia('(min-width: 1101px)').addEventListener('change', closeMenu);

const engineSelect = document.querySelector('select[name="engine"]');
const configurationSelect = document.querySelector('#configuration');
function syncEngineStages() {
  const customEngine = engineSelect.value !== '' && engineSelect.value !== '230 Kompressor';
  [...configurationSelect.options].forEach(option => {
    option.disabled = customEngine && option.value !== 'custom';
  });
  if (customEngine) configurationSelect.value = 'custom';
}
engineSelect.addEventListener('change', syncEngineStages);
document.querySelectorAll('[data-stage]').forEach(link => link.addEventListener('click', () => {
  if (link.dataset.stage !== 'custom') engineSelect.value = '230 Kompressor';
  syncEngineStages();
  configurationSelect.value = link.dataset.stage;
}));
syncEngineStages();

const imageDialog = document.querySelector('#image-dialog');
let captureTrigger;
document.querySelectorAll('[data-lightbox]').forEach(button => button.addEventListener('click', () => {
  captureTrigger = button;
  const image = document.querySelector('#dialog-image');
  image.src = button.dataset.lightbox;
  image.alt = button.querySelector('img')?.alt || button.dataset.caption;
  document.querySelector('#dialog-caption').textContent = button.dataset.caption;
  imageDialog.showModal();
}));
document.querySelector('.dialog-close').addEventListener('click', () => imageDialog.close());
imageDialog.addEventListener('click', event => {
  const bounds = imageDialog.getBoundingClientRect();
  if (event.target === imageDialog && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) imageDialog.close();
});
imageDialog.addEventListener('close', () => captureTrigger?.focus());

const form = document.querySelector('#consultation-form');
const formStatus = document.querySelector('#form-status');
function consultationText() {
  const values = new FormData(form);
  const labels = {custom: 'Custom setup / to be defined', stage1: 'Stage 1 · 230 Kompressor', stage2: 'Stage 2 · 230 Kompressor', stage3: 'Stage 3 · 230 Kompressor'};
  return `Hi Daniel,\n\nName: ${values.get('name')}\nEmail: ${values.get('email')}\n\nI would like to discuss a custom tune for my M111.\n\nModel: ${values.get('model')}\nEngine: ${values.get('engine')}\nConfiguration: ${labels[values.get('configuration')]}\n\nMy setup and goals:\n${values.get('message').trim()}\n\nThank you.`;
}
let submitting = false;
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (submitting || !form.reportValidity()) return;
  submitting = true;
  const button = form.querySelector('button[type="submit"]');
  const originalLabel = button.innerHTML;
  button.disabled = true;
  button.textContent = 'Sending…';
  form.setAttribute('aria-busy', 'true');
  formStatus.textContent = 'Sending your enquiry…';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);
  try {
    const tokenResponse = await fetch(form.action, {credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: {Accept: 'application/json'}});
    const session = await tokenResponse.json();
    if (!tokenResponse.ok || !session.token) throw new Error(session.message || 'Please try again or email Daniel directly.');
    const values = new FormData(form);
    values.set('token', session.token);
    const response = await fetch(form.action, {method: 'POST', body: values, credentials: 'same-origin', signal: controller.signal, headers: {Accept: 'application/json'}});
    const result = await response.json();
    if (!response.ok || result.ok !== true) throw new Error(result.message || 'Your enquiry could not be sent.');
    formStatus.textContent = result.message;
    form.reset();
    syncEngineStages();
  } catch (error) {
    formStatus.textContent = error.name === 'AbortError'
      ? 'The request timed out. Delivery could not be confirmed. Please email daniel@drasite.com before retrying.'
      : (error instanceof SyntaxError || error instanceof TypeError ? 'Could not connect to the mail service. Please email daniel@drasite.com directly.' : error.message);
  } finally {
    clearTimeout(timer);
    button.disabled = false;
    button.innerHTML = originalLabel;
    form.removeAttribute('aria-busy');
    submitting = false;
  }
});
document.querySelector('#copy-consultation').addEventListener('click', async () => {
  if (!form.reportValidity()) return;
  try {
    await navigator.clipboard.writeText(consultationText());
    formStatus.textContent = 'Enquiry copied. Paste it into an email to daniel@drasite.com or an Instagram message.';
  } catch {
    formStatus.textContent = 'Your browser could not copy the enquiry. Email daniel@drasite.com directly.';
  }
});
let toastTimer;
document.querySelectorAll('[data-download]').forEach(link => link.addEventListener('click', () => {
  const toast = document.querySelector('#toast');
  toast.textContent = `Opening ${link.dataset.download} from the official project repository.`;
  toast.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('visible'), 5000);
}));
const year = document.querySelector('#year');
if (year) year.textContent = new Date().getFullYear();
