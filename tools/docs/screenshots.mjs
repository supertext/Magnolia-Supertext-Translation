#!/usr/bin/env node
/**
 * Takes the screenshots used in docs/USER_GUIDE.md and docs/INSTALLATION.md from the demo.
 *
 *   DEMO_URL=http://localhost:8080 \
 *   DEMO_EDITOR_EMAIL=… DEMO_EDITOR_PASSWORD=… DEMO_ADMIN_EMAIL=… DEMO_ADMIN_PASSWORD=… \
 *   node screenshots.mjs            # writes ../../docs/images/*.png
 *
 * The demo must call stand-in.mjs (SUPERTEXT_API_ENDPOINT=http://host.docker.internal:8765/v1/,
 * without STAND_IN_PREFIX) so the German result is a real translation of the sample page, and
 * run without SUPERTEXT_API_KEY: the script stores a dummy key in the Supertext app like an
 * administrator would (the stand-in accepts any key). 1× scale, cropped to the relevant part.
 * See docs/DEVELOPER.md -> Docs screenshots.
 */
import { chromium } from 'playwright';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { samePicture } from './png.mjs';
import { dismissNotifications, fillTranslateDialog, openPages, openTranslateDialog, selectPage, signIn } from './magnolia.mjs';

const base = (process.env.DEMO_URL || 'http://localhost:8080').replace(/\/$/, '');
const need = ['DEMO_EDITOR_EMAIL', 'DEMO_EDITOR_PASSWORD', 'DEMO_ADMIN_EMAIL', 'DEMO_ADMIN_PASSWORD'].filter((v) => !process.env[v]);
if (need.length) {
  console.error(`Set ${need.join(', ')}.`);
  process.exit(2);
}
const images = new URL('../../docs/images/', import.meta.url).pathname;
mkdirSync(images, { recursive: true });
const shot = async (target, name, options = {}) => {
  // animations: 'disabled' finishes CSS transitions (button hover fades, checkbox ticks), which
  // otherwise leave a few different pixels from run to run.
  const png = await target.screenshot({ animations: 'disabled', ...options });
  // A shade of difference from Chromium's rendering is no UI change: keep the image then.
  if (existsSync(images + name) && samePicture(readFileSync(images + name), png)) {
    console.log('unchanged docs/images/' + name);
    return;
  }
  writeFileSync(images + name, png);
  console.log('wrote docs/images/' + name);
};
// Dates and times (the Pages app's "Modified" column, the key check's "Last checked") change with
// every run: replace them with a fixed one in the same format, so the images only change with the UI.
const freezeDates = (target) => target.evaluate(() => {
  const fixed = [
    [/\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* \d{1,2}, \d{4}(,? \d{1,2}:\d{2}(:\d{2})?( ?[AP]M)?)?/g, (m) => m.replace(/^\S+ \d{1,2}, \d{4}/, 'Oct 1, 2026').replace(/\d{1,2}:\d{2}(:\d{2})?/, (t) => (t.length > 5 ? '9:00:00' : '9:00'))],
    [/\b\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?/g, (m) => m.replace(/^\d{4}-\d{2}-\d{2}/, '2026-10-01').replace(/\d{2}:\d{2}(:\d{2}(\.\d+)?)?/, (t) => '09:00:00.000'.slice(0, t.length))],
    [/\b\d{1,2}\.\d{1,2}\.\d{2,4}(,? \d{1,2}:\d{2}(:\d{2})?)?/g, (m) => m.replace(/^\d{1,2}\.\d{1,2}\.\d{2,4}/, '01.10.2026').replace(/\d{1,2}:\d{2}(:\d{2})?/, (t) => (t.length > 5 ? '09:00:00' : '09:00'))],
  ];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    let v = n.nodeValue;
    for (const [re, to] of fixed) v = v.replace(re, to);
    if (v !== n.nodeValue) n.nodeValue = v;
  }
});
const box = async (locator, pad = 12) => {
  const b = await locator.boundingBox();
  return { x: Math.max(0, b.x - pad), y: Math.max(0, b.y - pad), width: b.width + 2 * pad, height: b.height + 2 * pad };
};

const browser = await chromium.launch();
const viewport = { width: 1400, height: 1000 };

// Administrator: store a (dummy) API key, as described in the installation guide.
let context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
let page = await context.newPage();
await signIn(page, base, process.env.DEMO_ADMIN_EMAIL, process.env.DEMO_ADMIN_PASSWORD);
await page.goto(`${base}/.magnolia/admincentral#app:supertext:settings`);
await page.waitForSelector('.supertext-settings', { timeout: 120000 }); // the first AdminCentral load after a fresh start is slow
await page.waitForTimeout(1500);
const keyField = page.locator('.supertext-settings input[type=password]');
if (await keyField.isEnabled() && !(await keyField.getAttribute('placeholder'))) {
  await keyField.fill('Supertext-Auth-Key docs-screenshots');
  await page.getByText('Save', { exact: true }).click();
  await page.locator('.v-Notification').first().waitFor();
}
await context.close();

// Editor: Pages app, dialog, result, retranslation.
context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
page = await context.newPage();
await signIn(page, base, process.env.DEMO_EDITOR_EMAIL, process.env.DEMO_EDITOR_PASSWORD);
await openPages(page, base);
await selectPage(page, 'supertext-demo');
await page.mouse.move(0, 0);
await freezeDates(page);
await shot(page, 'pages-action.png', { clip: { x: 0, y: 66, width: 1400, height: 690 } });

let dialog = await openTranslateDialog(page);
await fillTranslateDialog(dialog, ['de_CH', 'fr_CH'], { overwrite: true });
await page.mouse.move(0, 0);
await page.waitForTimeout(1000); // let the checkbox animations finish (else a few pixels differ per run)
await shot(page, 'translate-dialog.png', { clip: await box(dialog, 0) });
await dialog.locator('.v-select-optiongroup label', { hasText: '· fr_CH' }).click(); // German only
await dialog.getByRole('button', { name: 'Translate', exact: true }).click();
let note = page.locator('.v-Notification').first();
await note.waitFor({ timeout: 120000 });
await page.waitForTimeout(500);
await shot(page, 'translate-done.png', { clip: await box(note, 24) });
await dismissNotifications(page);

// Translating again without "Overwrite" keeps what is there.
dialog = await openTranslateDialog(page);
await fillTranslateDialog(dialog, ['de_CH']);
await dialog.getByRole('button', { name: 'Translate', exact: true }).click();
note = page.locator('.v-Notification').first();
await note.waitFor({ timeout: 120000 });
await page.waitForTimeout(500);
await shot(page, 'translate-kept.png', { clip: await box(note, 24) });
await dismissNotifications(page);

// The German page in the page editor (language selector at the bottom left).
await page.goto(`${base}/.magnolia/admincentral#app:pages-app:detail;/supertext-demo:edit`);
await page.waitForSelector('.language-selector', { timeout: 60000 });
await page.waitForTimeout(3000);
await page.locator('.language-selector').click();
await page.waitForTimeout(600);
await shot(page, 'languages.png', { clip: await box(page.locator('.v-filterselect-suggestpopup').first(), 40) });
await page.locator('.v-filterselect-suggestpopup').getByText(/German/).click();
await page.waitForTimeout(4000);
await page.mouse.move(0, 0);
await shot(page, 'translated-de.png', { clip: { x: 0, y: 66, width: 1400, height: 934 } });
await context.close();

// Administrator: the app launcher and the Supertext app (settings).
context = await browser.newContext({ viewport: { width: 1400, height: 1900 }, deviceScaleFactor: 1 });
page = await context.newPage();
await signIn(page, base, process.env.DEMO_ADMIN_EMAIL, process.env.DEMO_ADMIN_PASSWORD);
await dismissNotifications(page);
await page.goto(`${base}/.magnolia/admincentral#app-launcher`);
await page.waitForTimeout(2500);
await shot(page, 'app-launcher.png', { clip: await box(page.locator('.app-list.section', { hasText: 'Supertext' }).first(), 8) });
await page.goto(`${base}/.magnolia/admincentral#app:supertext:settings`);
await page.waitForSelector('.supertext-settings', { timeout: 60000 });
await page.waitForTimeout(2000);
// The ping address is the demo's own (local) URL: left out of the image.
await page.locator('.supertext-ping').evaluateAll((els) => els.forEach((el) => el.remove()));
await freezeDates(page);
const form = page.locator('.supertext-settings');
const height = Math.min(1830, Math.ceil((await form.boundingBox()).height) + 8);
await shot(page, 'settings.png', { clip: { x: 0, y: 66, width: 1180, height } }); // wide enough for the Language codes rows
await browser.close();
