#!/usr/bin/env node
/**
 * End-to-end check of the demo: signs in as the demo editor, translates the sample page into
 * German with "Translate with Supertext", and checks the German page shows translated text. Then
 * exports the page for translation (HTML), "translates" the file and imports it again.
 *
 *   DEMO_URL=http://localhost:8080 DEMO_EDITOR_EMAIL=… DEMO_EDITOR_PASSWORD=… node e2e.mjs
 *
 * Run it against a demo whose SUPERTEXT_API_ENDPOINT points at stand-in.mjs. Exits non-zero
 * on failure and writes out/e2e-failure.png. See docs/DEVELOPER.md -> Docs screenshots.
 */
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dismissNotifications, exportForTranslation, importTranslation, openPages, selectPage, signIn, translate } from './magnolia.mjs';

const base = (process.env.DEMO_URL || 'http://localhost:8080').replace(/\/$/, '');
const email = process.env.DEMO_EDITOR_EMAIL;
const password = process.env.DEMO_EDITOR_PASSWORD;
if (!email || !password) {
  console.error('Set DEMO_EDITOR_EMAIL and DEMO_EDITOR_PASSWORD.');
  process.exit(2);
}
mkdirSync(new URL('out/', import.meta.url), { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1400, height: 1100 } });
const page = await context.newPage();
try {
  await signIn(page, base, email, password);
  await openPages(page, base);
  await selectPage(page, 'supertext-demo');
  const message = await translate(page, ['de_CH'], { overwrite: true });
  console.log('Notification:', message.replace(/\s+/g, ' '));
  if (!/translated|übersetzt/i.test(message)) throw new Error('No success notification');

  const german = await context.newPage();
  await german.goto(`${base}/de_CH/supertext-demo.html`);
  const h1 = (await german.locator('h1').innerText()).trim();
  console.log('German title:', h1);
  if (!/Übersetzen mit Supertext|\[de-CH\]/.test(h1)) throw new Error(`German page not translated: ${h1}`);

  // Export for translation (HTML) → translate the file → import translation.
  await dismissNotifications(page);
  const exported = await exportForTranslation(page, ['de_CH'], 'html');
  console.log('Exported:', exported.name);
  if (!/^magnolia-.+-de_CH\.html$/.test(exported.name) || !exported.content.includes('supertext:reference')) {
    throw new Error(`Unexpected export: ${exported.name}`);
  }
  const translated = exported.content.replace(/(<div data-st-id="\d+">)/g, '$1[file] ');
  const upload = new URL(`out/${exported.name}`, import.meta.url).pathname;
  writeFileSync(upload, translated);
  const summary = await importTranslation(page, upload, { overwrite: true });
  console.log('Import:', summary.replace(/\s+/g, ' '));
  if (!/Imported: [1-9]/.test(summary)) throw new Error('Nothing imported');
  await german.reload();
  const imported = (await german.locator('h1').innerText()).trim();
  console.log('German title after import:', imported);
  if (!imported.includes('[file]')) throw new Error(`Import not written: ${imported}`);
  console.log('OK');
} catch (e) {
  await page.screenshot({ path: new URL('out/e2e-failure.png', import.meta.url).pathname });
  console.error(e);
  process.exitCode = 1;
} finally {
  await browser.close();
}
