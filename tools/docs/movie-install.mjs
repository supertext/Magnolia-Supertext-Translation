#!/usr/bin/env node
/**
 * Records the install and configuration movie (MP4 only, no GIF) from a FRESH demo whose module calls
 * stand-in.mjs (SUPERTEXT_API_ENDPOINT, SUPERTEXT_REPORT_URL) and that runs WITHOUT SUPERTEXT_API_KEY,
 * like screenshots.mjs. An administrator installs the module (terminal: the jar into Maven, the
 * dependency in the webapp's pom.xml, the build, the site's languages), opens the Supertext app, types
 * the made-up key st-demo-0000-0000-0000 (the stand-in accepts any key), saves (API key check), checks
 * the German language code and shows where editors find "Translate with Supertext".
 * See docs/DEVELOPER.md -> Demo movie.
 *
 *   DEMO_URL=http://localhost:8080 DEMO_ADMIN_EMAIL=… DEMO_ADMIN_PASSWORD=… node movie-install.mjs
 *
 * MOVIE_VOICE=0 makes a silent movie. MOVIE_OUT: where the MP4 goes (default
 * movie/out/Magnolia-Supertext-Translation-install.mp4, git-ignored, not committed). Needs ffmpeg.
 */
import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { MovieKit } from './movie/movie-kit.mjs';
import { dismissNotifications, signIn } from './magnolia.mjs';

const base = (process.env.DEMO_URL || 'http://localhost:8080').replace(/\/$/, '');
const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const out = process.env.MOVIE_OUT ?? here('movie/out/Magnolia-Supertext-Translation-install.mp4');
const need = (name) => process.env[name] ?? (() => { throw new Error(`Set ${name}`); })();
const FAKE_KEY = 'st-demo-0000-0000-0000';
const VERSION = '0.1.0';

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const newContext = () => browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, locale: 'en-US' });

/**
 * Signs in as the demo administrator (not recorded: configuration is an administrator's task) and opens the
 * Supertext app once. The first AdminCentral session after a fresh start sometimes never opens an app: then a
 * new session is used.
 */
async function adminSession() {
  for (let attempt = 1; ; attempt++) {
    const context = await newContext();
    const login = await context.newPage();
    await signIn(login, base, need('DEMO_ADMIN_EMAIL'), need('DEMO_ADMIN_PASSWORD'));
    await dismissNotifications(login);
    await login.goto(`${base}/.magnolia/admincentral#app:supertext:settings`);
    const ok = await login.waitForSelector('.supertext-settings', { timeout: 120000 }).then(() => true, () => false);
    if (ok) { await login.close(); return context; }
    await context.close();
    if (attempt === 3) throw new Error('AdminCentral did not open the Supertext app');
  }
}
const context = await adminSession();

// The movie is recorded in a new tab (same session; AdminCentral's tab can't be turned into the terminal).
const page = await context.newPage();
const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'magnolia-movie-install-'));
const kit = await MovieKit.start(page, { workDir, assets: here('movie/assets'), voiceCache: here('movie/voice-cache') });
const ripple = () => page.evaluate(([x, y]) => window.__stk?.ripple(x, y), [kit.cursor.x, kit.cursor.y]);

/** More commands in the terminal kit.terminal() opened (same look; kit.terminal() would start a new one). */
async function typeMore(lines) {
  await page.evaluate(() => document.querySelector('#x > .p.c:last-child')?.remove());
  for (const l of lines) {
    if (l.cmd !== undefined) {
      await page.evaluate(() => { const x = document.getElementById('x');
        const p = document.createElement('span'); p.className = 'p'; p.textContent = '$ '; x.appendChild(p);
        const c = document.createElement('span'); c.className = 'c'; x.appendChild(c); });
      for (const ch of l.cmd) { await page.evaluate((ch) => { document.querySelector('#x .c').textContent += ch; }, ch); await page.waitForTimeout(38); }
      await page.waitForTimeout(450);
      await page.evaluate(() => { document.querySelector('#x .c').classList.remove('c'); document.getElementById('x').append('\n'); });
    } else {
      await page.evaluate((o) => { const s = document.createElement('span'); s.className = 'o'; s.textContent = o + '\n'; document.getElementById('x').appendChild(s); }, l.out ?? '');
      await page.waitForTimeout(l.wait ?? 180);
    }
  }
  await page.evaluate(() => { const c = document.createElement('span'); c.className = 'p c'; c.textContent = '$ '; document.getElementById('x').appendChild(c); });
}
/** Clears the terminal (a new screen for the next command group). */
const clearTerminal = () => page.evaluate(() => { document.getElementById('x').textContent = ''; });

// 1. Terminal: the steps from docs/INSTALLATION.md (output shortened).
await kit.say('The module is one jar, attached to every GitHub release. Install it into your Maven repository.', {
  caption: 'Install the release jar into <b>Maven</b>',
  during: () => kit.terminal({ title: 'my-site', lines: [
    { cmd: `mvn install:install-file -Dfile=magnolia-supertext-translation-${VERSION}.jar \\` },
    { out: `    -DgroupId=com.supertext -DartifactId=magnolia-supertext-translation -Dversion=${VERSION} -Dpackaging=jar` },
    { out: `[INFO] Installing magnolia-supertext-translation-${VERSION}.jar to ~/.m2/repository/com/supertext/…` },
    { out: '[INFO] BUILD SUCCESS' },
  ] }),
});
await kit.say("Add it as a dependency to your webapp's p o m file.", {
  caption: 'Add the dependency to <b>pom.xml</b>',
  during: async () => {
    await clearTerminal();
    await typeMore([
      { cmd: 'git diff webapp/pom.xml' },
      { out: '+    <dependency>' },
      { out: '+      <groupId>com.supertext</groupId>' },
      { out: '+      <artifactId>magnolia-supertext-translation</artifactId>' },
      { out: `+      <version>${VERSION}</version>` },
      { out: '+    </dependency>' },
    ]);
  },
});
await kit.say('Build the webapp.', {
  caption: 'Build the webapp: <b>mvn package</b>',
  during: () => typeMore([
    { cmd: 'mvn package' },
    { out: '[INFO] Building war: webapp/target/ROOT.war' },
    { out: '[INFO] BUILD SUCCESS' },
  ]),
});
await kit.say('Deploy the webapp and restart Magnolia. It installs the module by itself.', {
  caption: 'Deploy and restart: Magnolia installs the module',
  during: () => typeMore([
    { cmd: 'tail logs/magnolia-debug.log' },
    { out: 'INFO  info.magnolia.module.ModuleManagerImpl: Initializing module supertext-translation' },
  ]),
});
await kit.say("Pages are translated from the site's main language into its other languages, set in the site definition.", {
  caption: 'Site languages: <b>i18n</b> in the site definition',
  during: async () => {
    await clearTerminal();
    await typeMore([
      { cmd: 'cat light-modules/my-site/sites/my-site.yaml' },
      { out: 'i18n:' },
      { out: '  enabled: true' },
      { out: '  fallbackLocale: en' },
      { out: '  locales:' },
      { out: '    en: { language: en, enabled: true }' },
      { out: '    de_CH: { language: de, country: CH, enabled: true }' },
      { out: '    fr_CH: { language: fr, country: CH, enabled: true }' },
    ]);
  },
});
await kit.hold(300);

// 2. AdminCentral as administrator: the app launcher → Translation → Supertext (loading time cut).
kit.pause();
await page.goto(`${base}/.magnolia/admincentral#app-launcher`);
await page.waitForSelector('.app-list.section', { timeout: 60000 });
await page.waitForTimeout(2000);
await kit.sync();
kit.resume();
const tile = page.locator('.app-list.section', { hasText: 'Supertext' }).getByText('Supertext', { exact: true }).first();
await kit.say('Sign in to AdminCentral as a superuser. In the app launcher, open Supertext, under Translation.', {
  caption: 'App launcher → <b>Translation</b> → <b>Supertext</b>',
  during: async () => {
    await kit.hold(700);
    await kit.click(tile);
    await page.waitForSelector('.supertext-settings', { timeout: 60000 });
    await page.waitForTimeout(1500);
    // The ping address is the demo's own (local) URL: never in the movie.
    await page.locator('.supertext-ping').evaluateAll((els) => els.forEach((el) => el.remove()));
    await kit.sync();
  },
});

// 3. The API key.
const keyField = page.locator('.supertext-settings input[type=password]').first();
await kit.say('Generate your API key at supertext.com, under Integrations, A P I, with the Admin role. No account yet? Create one at supertext.com.', {
  caption: 'Key: <b>supertext.com → Integrations → API</b> (Admin)<br>No account yet? Create one at <b>supertext.com</b>',
  during: async () => { await kit.hold(600); await kit.moveTo(keyField); },
});
await kit.say('Paste the key into the field API key.', {
  caption: 'Paste it into <b>API key</b>',
  during: () => kit.type(keyField, FAKE_KEY),
});

// 4. Language codes (scrolled into view).
const scroller = async (locator) => {
  await locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
  await page.waitForTimeout(900);
};
const grid = page.locator('.supertext-languages').first();
const deBox = page.locator('.supertext-language-de_CH').first();
await kit.say('Under Language codes, each site language has its Supertext language, already preselected.', {
  caption: '<b>Language codes</b>: one per site language',
  during: async () => { await scroller(grid); await kit.sync(); await kit.moveTo(deBox); },
});
await kit.say('Open one to choose another variant if you need it. For de C H we keep German, Switzerland.', {
  caption: 'Keep <b>German (Switzerland) (de-CH)</b> for de_CH',
  during: async () => {
    const input = deBox.locator('input').first();
    await kit.click(input);
    await input.press('Control+a');
    await input.pressSequentially('German', { delay: 90 });
    const popup = page.locator('.v-filterselect-suggestpopup').first();
    await popup.waitFor();
    await kit.hold(700);
    await kit.click(popup.getByText('German (Switzerland) (de-CH)', { exact: true }).first());
    await page.waitForTimeout(400);
    await kit.sync();
  },
});
const review = page.locator('.supertext-review-de_CH').first();
await kit.say('Translation review can be switched on per language. We leave it off.', {
  caption: '<b>Translation review</b>: per language, left off',
  during: async () => { await kit.hold(300); await kit.moveTo(review); },
});

// 5. Save → API key check.
const save = page.locator('.supertext-settings .v-button-commit').first();
const ok = page.getByText('the connection to Supertext works', { exact: false }).first();
await kit.say('Click Save. Magnolia stores the settings and checks the key with Supertext.', {
  caption: 'Click <b>Save</b>: the API key is checked',
  during: async () => {
    await scroller(save);
    await kit.sync();
    await kit.click(save);
    await ok.waitFor({ timeout: 60000 });
    await page.waitForTimeout(400);
    await dismissNotifications(page);
    await kit.sync();
  },
});
await kit.say('The connection to Supertext works.', {
  caption: 'Key check: <b>the connection to Supertext works</b>',
  during: async () => { await scroller(ok); await kit.sync(); await kit.moveTo(ok); },
});

// 6. Where editors find it.
kit.pause();
await page.goto(`${base}/.magnolia/admincentral#app:pages-app:browser;:treeview:`);
await page.waitForSelector('.v-actionbar', { timeout: 60000 });
const row = page.locator('tr', { hasText: '/supertext-demo' }).first();
await row.waitFor({ timeout: 30000 });
await page.waitForTimeout(1500);
await kit.sync();
kit.resume();
const action = page.locator('.v-actionbar').getByText('Translate with Supertext', { exact: true }).first();
await kit.say("That's it. Editors now find Translate with Supertext in the Pages app.", {
  caption: 'Editors find <b>Translate with Supertext</b> in <b>Pages</b>',
  during: async () => {
    await kit.click(row);
    await page.waitForTimeout(800);
    await kit.moveTo(action);
    await action.hover();
  },
  after: 300,
});
await kit.caption('');

fs.mkdirSync(path.dirname(out), { recursive: true });
await kit.finish(out, { system: 'Magnolia', repo: 'Magnolia-Supertext-Translation', kind: 'install' });
await browser.close();
fs.rmSync(workDir, { recursive: true, force: true });
console.log(`Saved ${out}`);
