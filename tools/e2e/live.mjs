#!/usr/bin/env node
/**
 * End-to-end test against the REAL Supertext API: a fresh demo (nothing translated yet) with a
 * real SUPERTEXT_API_KEY. The demo EDITOR translates the sample page into every other site
 * language with "Translate with Supertext" in the Pages app (Magnolia's own UI), then the
 * content is read back from the JCR through Magnolia's REST nodes endpoint (as the demo
 * administrator; editors have no REST access) and checked: every translatable property has a
 * translation in each language, nothing was published, the texts changed and are in the target
 * language (de-CH without "ß"), no protocol leftovers, formatting and links kept, the author
 * (not translatable) untouched, and translating again updates the same nodes in place.
 * See "End-to-end tests" in docs/DEVELOPER.md.
 *
 *   DEMO_URL=http://127.0.0.1:8081 EDITOR_EMAIL=… EDITOR_PASSWORD=… \
 *   ADMIN_EMAIL=… ADMIN_PASSWORD=… node tools/e2e/live.mjs
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dismissNotifications, fillTranslateDialog, openPages, openTranslateDialog, selectPage, signIn } from '../docs/magnolia.mjs';

const B = (process.env.DEMO_URL ?? 'http://127.0.0.1:8081').replace(/\/$/, '');
const need = (name) => process.env[name] || (() => { throw new Error(`Set ${name}`); })();
const artifacts = fileURLToPath(new URL('./artifacts/', import.meta.url));
mkdirSync(artifacts, { recursive: true });

const failures = [];
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
  return ok;
}

// Common words, to tell the target languages apart. Lower case, whole words.
const STOPWORDS = {
  en: ['the', 'and', 'is', 'are', 'with', 'for', 'you', 'your', 'our', 'of', 'to', 'we', 'who', 'what'],
  de: ['der', 'die', 'das', 'und', 'ist', 'sind', 'mit', 'für', 'sie', 'ihre', 'ihr', 'unser', 'unsere', 'wir', 'eine', 'ein', 'den', 'zu', 'von', 'was'],
  fr: ['le', 'les', 'et', 'est', 'sont', 'avec', 'pour', 'vous', 'votre', 'vos', 'notre', 'nos', 'nous', 'des', 'une', 'un', 'du', 'de', 'qui', 'que'],
  it: ['il', 'lo', 'gli', 'e', 'è', 'sono', 'con', 'per', 'lei', 'sua', 'suo', 'nostro', 'nostra', 'noi', 'una', 'un', 'del', 'della', 'di', 'che', 'chi'],
};
const words = (text) => text.toLowerCase().match(/[\p{L}]+/gu) ?? [];
const score = (text, lang) => words(text).filter((w) => STOPWORDS[lang].includes(w)).length;
const plain = (v) => String(v ?? '').replace(/<[^>]+>/g, ' ');
const count = (haystack, re) => (String(haystack ?? '').match(re) ?? []).length;
const hrefs = (html) => [...String(html ?? '').matchAll(/href="([^"]*)"/g)].map((m) => m[1]).sort().join(' ');

// The demo's translatable fields (i18n: true in demo/src/main/resources/supertext-demo/dialogs)
// and one that must stay as it is (the quote's author has no i18n).
const FIELDS = ['title', 'intro', 'headline', 'text', 'quote'];
const UNTRANSLATED = ['author'];
const LEFTOVERS = ['data-st-id', 'data-st-ph', 'st-ph-', '<div data-st'];
const SAMPLE = '/supertext-demo';
// Magnolia locale (site i18n, property suffix) → expected language. From the demo's site definition.
const LOCALES = (process.env.E2E_LOCALES ?? 'de_CH,fr_CH,it_CH').split(',').map((l) => l.trim()).filter(Boolean);
// What formatting the rich text must keep, counted per field.
const MARKUP = [
  ['links', /<a\s/g],
  ['bold', /<(strong|b)[\s>]/g],
  ['italics', /<(em|i)[\s>]/g],
  ['list items', /<li[\s>]/g],
  ['lists', /<(ul|ol)[\s>]/g],
  ['headings', /<h[1-6][\s>]/g],
  ['paragraphs', /<p[\s>]/g],
  ['Magnolia links', /\$\{link:/g],
];

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const viewport = { width: 1400, height: 1100 };

// --- The JCR through Magnolia's REST nodes endpoint (rest-services, superuser) -------------
const adminContext = await browser.newContext({ viewport, locale: 'en-US' });
const adminPage = await adminContext.newPage();
await signIn(adminPage, B, need('ADMIN_EMAIL'), need('ADMIN_PASSWORD'));

/** The sample page and all nodes below it, flattened: [{ path, id, type, props: {name: value} }]. */
async function snapshot() {
  const res = await adminContext.request.get(`${B}/.rest/nodes/v1/website${SAMPLE}?depth=10&includeMetadata=true`, { headers: { Accept: 'application/json' } });
  const body = await res.text();
  if (!res.ok() || !body.trim().startsWith('{')) {
    throw new Error(`REST nodes endpoint: HTTP ${res.status()} ${body.slice(0, 300)}`);
  }
  const out = [];
  const walk = (n) => {
    const props = {};
    for (const p of n.properties ?? []) props[p.name] = Array.isArray(p.values) ? (p.multiple ? p.values : p.values[0]) : p.value;
    out.push({ path: n.path, id: n.identifier, type: n.type, props });
    for (const c of n.nodes ?? []) walk(c);
  };
  walk(JSON.parse(body));
  return out;
}

// --- Setup ----------------------------------------------------------------------------------
let before;
try {
  before = await snapshot();
} catch (e) {
  check('REST nodes endpoint answers for the sample page (as the demo administrator)', false, e.message);
  await browser.close();
  console.error(`\n${failures.length} check(s) failed:\n- ${failures.join('\n- ')}`);
  process.exit(1);
}
const sourceFields = before.flatMap((n) => FIELDS.filter((f) => plain(n.props[f]).trim()).map((f) => ({ node: n, field: f, value: String(n.props[f]) })));
console.log(`Sample page: ${before.length} nodes, ${sourceFields.length} translatable fields (${sourceFields.map((s) => `${s.node.path}@${s.field}`).join(', ')}); targets: ${LOCALES.join(', ')}`);
check('sample page has translatable fields', sourceFields.length > 0);
check('fresh demo (nothing translated yet)', before.every((n) => !Object.keys(n.props).some((k) => LOCALES.some((l) => k.endsWith(`_${l}`)))));
const published = (n) => n.props['mgnl:activationStatus'] === true || n.props['mgnl:activationStatus'] === 'true';
const publishedBefore = new Set(before.filter(published).map((n) => n.path));

// --- Editor: Pages app → Translate with Supertext ----------------------------------------------
const context = await browser.newContext({ viewport, locale: 'en-US' });
const page = await context.newPage();
page.setDefaultTimeout(60_000);
try {
  await signIn(page, B, need('EDITOR_EMAIL'), need('EDITOR_PASSWORD'));
  await openPages(page, B);
  check('editor can sign in and open the Pages app (DEMO_EDITOR_*)', true);
} catch (e) {
  await page.screenshot({ path: `${artifacts}login.png` });
  check('editor can sign in and open the Pages app (DEMO_EDITOR_*)', false, e.message);
  await browser.close();
  console.error(`\n${failures.length} check(s) failed:\n- ${failures.join('\n- ')}`);
  process.exit(1);
}

async function translateSample(label) {
  await openPages(page, B);
  await selectPage(page, SAMPLE.slice(1));
  const dialog = await openTranslateDialog(page);
  for (const l of LOCALES) {
    check(`${label}: ${l} offered in the dialog`, (await dialog.locator('.v-select-optiongroup label', { hasText: `· ${l}` }).count()) === 1);
  }
  await fillTranslateDialog(dialog, LOCALES, { overwrite: true });
  await page.screenshot({ path: `${artifacts}${label}-dialog.png` });
  const started = Date.now();
  await dialog.getByRole('button', { name: 'Translate', exact: true }).click();
  // Translation runs inside the dialog's request, one language after the other.
  const note = page.locator('.v-Notification').first();
  await note.waitFor({ timeout: 900_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${artifacts}${label}-result.png` });
  const text = (await note.innerText()).replace(/\s+/g, ' ').trim();
  const cls = (await note.getAttribute('class')) ?? '';
  console.log(`${label}: ${Math.round((Date.now() - started) / 1000)} s — ${text}`);
  check(`${label}: success notification`, /^Translated "/.test(text) && !/with problems/.test(text), text);
  check(`${label}: no error or warning shown`, !/\b(error|warning)\b/.test(cls) && (await page.locator('.v-Notification.error, .v-Notification.warning').count()) === 0, cls);
  check(`${label}: dialog closed`, (await page.locator('.v-window').filter({ hasText: 'Translate into' }).count()) === 0);
  await dismissNotifications(page);
}

// --- First translation ------------------------------------------------------------------------
await translateSample('first-run');
const first = await snapshot();
const byPath = (snap) => new Map(snap.map((n) => [n.path, n]));
const firstByPath = byPath(first);

check('no node added or removed by translating', first.map((n) => n.path).sort().join(',') === before.map((n) => n.path).sort().join(','), `${before.length} → ${first.length}`);
check('nothing published by translating', first.filter((n) => published(n) && !publishedBefore.has(n.path)).length === 0, first.filter(published).map((n) => n.path).join(', '));

for (const locale of LOCALES) {
  const lang = locale.split(/[-_]/)[0].toLowerCase();
  const tag = locale;
  let fields = 0;
  let changed = 0;
  let text = '';
  for (const s of sourceFields) {
    const n = firstByPath.get(s.node.path);
    const name = `${s.field}_${locale}`;
    const v = String(n?.props[name] ?? '');
    fields++;
    if (!check(`${tag}: ${s.node.path}@${name} filled`, plain(v).trim() !== '')) continue;
    if (v.trim() !== s.value.trim()) changed++;
    else console.log(`note ${tag}: ${s.node.path}@${s.field} unchanged ("${plain(s.value).trim().slice(0, 60)}")`);
    text += ` ${plain(v)}`;
    const left = LEFTOVERS.filter((l) => v.includes(l));
    check(`${tag}: ${s.node.path}@${name} has no protocol leftovers`, left.length === 0, left.join(', '));
    for (const [what, re] of MARKUP) {
      const want = count(s.value, re);
      if (want) check(`${tag}: ${s.node.path}@${name} keeps its ${what}`, count(v, re) === want, `${count(v, re)}/${want}`);
    }
    if (hrefs(s.value)) check(`${tag}: ${s.node.path}@${name} keeps its link targets`, hrefs(v) === hrefs(s.value), hrefs(v));
  }
  for (const n of first) {
    for (const f of UNTRANSLATED) {
      if (n.props[f] === undefined) continue;
      const t = n.props[`${f}_${locale}`];
      check(`${tag}: ${n.path}@${f} (not translatable) left alone`, t === undefined || t === n.props[f], String(t));
    }
  }
  check(`${tag}: texts were changed by the translation`, fields > 0 && changed / fields >= 0.8, `${changed}/${fields} fields`);
  if (STOPWORDS[lang]) {
    const scores = Object.fromEntries(Object.keys(STOPWORDS).map((l) => [l, score(text, l)]));
    const best = Object.entries(scores).sort((a, b) => b[1] - a[1])[0][0];
    check(`${tag}: text is in the target language`, best === lang, JSON.stringify(scores));
  }
  if (/^de[-_]ch$/i.test(locale)) check(`${tag}: Swiss German spelling (no "ß")`, !text.includes('ß'));

  // What visitors see: the rendered page in that language (the editor's session; author instance).
  const view = await context.newPage();
  try {
    const res = await view.goto(`${B}/${locale}${SAMPLE}.html`);
    const html = await view.content();
    await view.screenshot({ path: `${artifacts}page-${locale}.png`, fullPage: true });
    const title = String(firstByPath.get(SAMPLE)?.props[`title_${locale}`] ?? '').trim();
    const h1 = (await view.locator('h1').first().innerText()).trim();
    check(`${tag}: rendered page shows the translated title`, res?.ok() && h1 === title, `HTTP ${res?.status()} "${h1}"`);
    const left = LEFTOVERS.filter((l) => html.includes(l));
    check(`${tag}: rendered page has no protocol leftovers`, left.length === 0, left.join(', '));
  } catch (e) {
    check(`${tag}: rendered page opens`, false, e.message);
  } finally {
    await view.close();
  }
}

// --- Translating again updates in place ---------------------------------------------------------
await translateSample('second-run');
const second = await snapshot();
const id = (snap) => snap.map((n) => `${n.path}#${n.id}`).sort().join(',');
check('translating again updates the same nodes (no duplicates)', id(second) === id(first), `${first.length} → ${second.length}`);
const translatedProps = (snap) => snap.flatMap((n) => Object.keys(n.props).filter((k) => LOCALES.some((l) => k.endsWith(`_${l}`))).map((k) => `${n.path}@${k}`)).sort().join(',');
check('translating again writes the same properties (none added)', translatedProps(second) === translatedProps(first), translatedProps(second));
const secondByPath = byPath(second);
const stillFilled = sourceFields.every((s) => LOCALES.every((l) => plain(secondByPath.get(s.node.path)?.props[`${s.field}_${l}`]).trim() !== ''));
check('translating again leaves every field filled', stillFilled);
check('nothing published by translating again', second.filter((n) => published(n) && !publishedBefore.has(n.path)).length === 0);

await browser.close();

if (failures.length) {
  console.error(`\n${failures.length} check(s) failed:\n- ${failures.join('\n- ')}`);
  process.exit(1);
}
console.log('\nAll end-to-end checks passed.');
