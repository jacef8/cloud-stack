// Builds public/index.html from the standalone page and adds a
// Content-Security-Policy <meta> tag. The page has one inline script, so the
// policy allows exactly that script by its SHA-256 hash: any script injected
// into the page (for example through a league member's display name) is
// refused by the browser. The hash is recomputed on every build, so editing
// the script never needs a manual policy update.
//
// Directives a <meta> tag cannot carry (frame-ancestors etc.) live in the
// headers block of firebase.json.
const fs = require('fs');
const crypto = require('crypto');

const SRC = 'samples/record-room-standalone.html';
const OUT = 'public/index.html';

const html = fs.readFileSync(SRC, 'utf8');
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)];
if (scripts.length !== 1) {
  console.error(`Expected exactly one inline <script> in ${SRC}, found ${scripts.length}.`);
  process.exit(1);
}
if (/<script[^>]*\bsrc=/.test(html) || /\son[a-z]+\s*=\s*["']/i.test(html.replace(/<script[\s\S]*?<\/script>/g, ''))) {
  console.error('External scripts or inline event handlers would be blocked by the CSP.');
  process.exit(1);
}

const hash = crypto.createHash('sha256').update(scripts[0][1]).digest('base64');
const csp = [
  "default-src 'none'",
  `script-src 'sha256-${hash}'`,
  "style-src 'unsafe-inline' https://fonts.googleapis.com",
  'font-src https://fonts.gstatic.com',
  "img-src 'self' data:",
  'connect-src https://api.sleeper.app',
].join('; ');

const meta = `<meta http-equiv="Content-Security-Policy" content="${csp}">`;
const out = html.replace(/(<meta charset="utf-8">)/i, `$1\n${meta}`);
if (out === html) {
  console.error('Could not find <meta charset="utf-8"> to place the CSP after.');
  process.exit(1);
}

fs.mkdirSync('public', { recursive: true });
fs.writeFileSync(OUT, out);
console.log(`Wrote ${OUT} (script hash sha256-${hash.slice(0, 12)}…)`);
