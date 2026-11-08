#!/usr/bin/env node
/**
 * Records the demo movie (docs/images/demo.gif and an MP4) from the demo, like screenshots.mjs: the
 * demo's module calls stand-in.mjs (SUPERTEXT_API_ENDPOINT, without STAND_IN_PREFIX) so the German
 * result is a real translation of the sample page, and an API key is stored (the stand-in accepts any).
 * The editor opens the sample page in the page editor, runs "Translate with Supertext" into German
 * and switches the editor to German. See docs/DEVELOPER.md -> Demo movie.
 *
 *   DEMO_URL=http://localhost:8080 DEMO_EDITOR_EMAIL=… DEMO_EDITOR_PASSWORD=… node movie.mjs
 *
 * Voice: Piper (pip install piper-tts), voice "alba" downloaded once into movie/voice-cache/
 * (git-ignored); MOVIE_VOICE=0 makes a silent movie. MOVIE_OUT: where the MP4 goes (default
 * movie/out/Magnolia-Supertext-Translation.mp4, git-ignored; the MP4 is not committed). Needs ffmpeg.
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
const out = process.env.MOVIE_OUT ?? here('movie/out/Magnolia-Supertext-Translation.mp4');
const gif = here('../../docs/images/demo.gif');
const need = (name) => process.env[name] ?? (() => { throw new Error(`Set ${name}`); })();

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, locale: 'en-US' });

// Sign in as the demo editor and open the sample page in the page editor (not recorded).
await signIn(page, base, need('DEMO_EDITOR_EMAIL'), need('DEMO_EDITOR_PASSWORD'));
await dismissNotifications(page);
await page.goto(`${base}/.magnolia/admincentral#app:pages-app:detail;/supertext-demo:edit`);
await page.waitForSelector('.language-selector', { timeout: 60000 });
const preview = page.frameLocator('iframe').first();
await preview.locator('h1').first().waitFor({ timeout: 60000 });
await page.waitForTimeout(2000);

const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'magnolia-movie-'));
const kit = await MovieKit.start(page, { workDir, assets: here('movie/assets'), voiceCache: here('movie/voice-cache') });
const ripple = () => page.evaluate(([x, y]) => window.__stk?.ripple(x, y), [kit.cursor.x, kit.cursor.y]);

// 1. The English page in the page editor.
await kit.say("Here's a page in English, open in Magnolia's page editor.", {
  caption: 'A page in English',
  during: async () => { await kit.hold(600); await kit.moveTo(preview.locator('h1').first()); },
});

// 2. Translate with Supertext (action bar on the right).
const action = page.locator('.v-actionbar').getByText('Translate with Supertext', { exact: true }).first();
const dialog = page.locator('.v-window').filter({ hasText: 'Translate into' }).first();
await kit.say('In the action bar, click Translate with Supertext.', {
  caption: 'Click <b>Translate with Supertext</b>',
  during: () => kit.click(action),
  after: 0,
});
kit.pause(); // the dialog's loading time is cut
await dialog.waitFor({ timeout: 30000 });
await page.waitForTimeout(500);
await kit.sync();
kit.resume();

// 3. German, then Translate.
const german = dialog.locator('.v-select-optiongroup label', { hasText: '· de_CH' });
const go = dialog.getByRole('button', { name: 'Translate', exact: true });
await kit.say('Tick German, then click Translate.', {
  caption: 'Tick <b>German (Switzerland)</b>, click <b>Translate</b>',
  during: async () => { await kit.click(german); await kit.hold(400); await kit.moveTo(go); },
  after: 0,
});

// 4. Waiting: the progress state for a moment, then the waiting time is cut. The stand-in answers at once,
// so AdminCentral's request for the click is held back until the progress state has been seen.
const note = page.locator('.v-Notification').first();
let release;
const released = new Promise((resolve) => (release = resolve));
await page.route(/\/UIDL\//, async (route) => {
  if (release && route.request().method() === 'POST') await released;
  await route.continue();
});
await ripple();
await go.click({ noWaitAfter: true });
await kit.say('Supertext translates the content.', { caption: 'Supertext translates the content…', after: 0 });
release();
kit.pause();
await note.waitFor({ timeout: 300000 });
await page.waitForTimeout(400);
kit.resume();
await kit.sync();

// 5. The result.
await kit.say('A few seconds later, the texts are translated into German, and saved in the page.', {
  caption: 'Done: the texts are translated into German',
  during: () => kit.moveTo(note),
});
const selector = page.locator('.language-selector');
await kit.say("Let's switch the page editor to German, and check it.", {
  caption: 'Switch the page editor to <b>German</b>',
  during: async () => {
    await kit.click(note);
    await page.waitForTimeout(300);
    await kit.click(selector);
    const option = page.locator('.v-filterselect-suggestpopup').getByText(/German/).first();
    await option.waitFor();
    await kit.hold(400);
    await kit.click(option);
  },
  after: 0,
});
kit.pause(); // the German preview's loading time is cut
await preview.locator('h1', { hasText: 'Übersetzen' }).first().waitFor({ timeout: 60000 });
await page.waitForTimeout(800);
await kit.sync();
kit.resume();
await kit.say('The German version is ready. Review it, and publish it as usual.', {
  caption: 'The German version is ready – review it and publish',
  during: async () => { await kit.hold(300); await kit.moveTo(preview.locator('h1').first()); },
});
await kit.caption('');

fs.mkdirSync(path.dirname(out), { recursive: true });
await kit.finish(out, { system: 'Magnolia', repo: 'Magnolia-Supertext-Translation', gif });
await browser.close();
fs.rmSync(workDir, { recursive: true, force: true });
console.log(`Saved ${out} and ${gif} (${(fs.statSync(gif).size / 1e6).toFixed(2)} MB)`);
