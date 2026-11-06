#!/usr/bin/env node
'use strict';

/*
 * Secure Wallet Pro - Blogger (Blogspot) companion theme generator.
 *
 * Emits the complete Blogger Layouts v2 XML theme (one self-contained file,
 * inline JS/CSS) that mirrors the Android vault against the same Firebase
 * Realtime Database backend: Google sign-in -> same uid -> keyring unwrap ->
 * live per-record listeners. Optionally embeds a passphrase-sealed .enc
 * export (Backup -> Export, envelope v3) as an offline snapshot.
 *
 * The theme only ever contains ciphertext plus the public Firebase project
 * identifiers read from google-services.json. Decryption happens in the
 * visitor's browser after they type their passphrase or vault keyring
 * passphrase; no key material is ever written to the file.
 *
 * Usage:
 *   node generate.js --out Blogger-Theme-3.2.2.xml --title "Secure Wallet Pro"
 *   node generate.js --in vault-export.enc --out theme.xml --drive-client-id 1234-abc.apps.googleusercontent.com
 *
 * Zero dependencies. Node >= 18.
 */

const fs = require('fs');
const path = require('path');

const ITERATIONS = 600000;
const DEFAULT_PACKAGE = 'com.securevault.app';
const DEFAULT_RTDB_REGION = 'asia-southeast1';
const DEFAULT_FIREBASE_VERSION = '10.12.2';
const DEFAULT_PALETTE_PATH = path.resolve(
  __dirname,
  '../../app/src/main/java/com/securevault/pro/presentation/theme/Palette.kt'
);

function usage() {
  process.stdout.write(
    'Secure Wallet Pro - Blogger theme generator\n\n' +
    '  node generate.js [options]\n\n' +
    'Options:\n' +
    '  --in <file>               Passphrase-sealed .enc export from the app (envelope v3 JSON).\n' +
    '                            Omit to build a theme that unlocks a file picked in the browser.\n' +
    '  --out <file>              Output XML path (default: secure_wallet_blogger_theme.xml).\n' +
    '  --title <text>            Blog / vault title shown in the header.\n' +
    '  --drive-client-id <id>    Override the Google Web Client ID used for sign-in and Drive.\n' +
    '  --google-services <file>  google-services.json (default: ../../google-services.json).\n' +
    '  --package <id>            Android package selecting the client (default com.securevault.app).\n' +
    '  --firebase-api-key <key>  Override the Firebase web API key read from google-services.json.\n' +
    '  --rtdb-region <region>    Realtime Database region (default asia-southeast1).\n' +
    '  --database-url <url>      Full Realtime Database URL when the instance is not <project>-default-rtdb.\n' +
    '  --firebase-version <v>    Firebase JS modular version served from gstatic (default 10.12.2).\n' +
    '  --help                    Show this help.\n\n' +
    'Security: the theme embeds ciphertext only. No passphrase and no vault key is ever written.\n' +
    'The Firebase project id, OAuth web client id and the public web API key are read from\n' +
    'google-services.json at build time - none is hardcoded in this generator, and a value shaped\n' +
    'like an OAuth client secret is rejected outright. Reading data still needs a signed-in\n' +
    'session whose Firebase uid equals the node path.\n'
  );
}

/* An API key and an OAuth client id are public client identifiers that authorise nothing
   alone. Anything shaped like an OAuth client secret is refused so a secret can never be
   pasted into a theme by mistake. */
function assertNotSecret(name, value) {
  const text = String(value || '');
  if (!text) return text;
  if (text.includes('--') || /^goog/i.test(text) || /client[_-]?secret/i.test(text)) {
    throw new Error(name + ' looks like an OAuth client secret. A theme must never carry one.');
  }
  if (!/^AIza[0-9A-Za-z_-]{20,60}$/.test(text)) {
    throw new Error(name + ' is not a Firebase web API key (expected AIza plus 20-60 key characters).');
  }
  return text;
}

function assertPublicId(name, value) {
  const text = String(value || '');
  if (/[\s<&]/.test(text) || text.includes(']]>')) {
    throw new Error(name + ' is not a safe identifier for a theme: ' + text.slice(0, 24));
  }
  return text;
}

function readFirebaseConfig(file, pkg, overrides) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (error) {
    process.stderr.write('Warning: ' + file + ' is not readable (' + error.message + '). Keeping the template defaults.\n');
    return null;
  }
  let config;
  try {
    config = JSON.parse(raw.replace(/^/, ''));
  } catch (error) {
    throw new Error('google-services.json is not valid JSON: ' + error.message);
  }
  const projectId = String((config.project_info || {}).project_id || '').trim();
  if (!projectId) throw new Error('google-services.json has no project_id.');
  const clients = Array.isArray(config.client) ? config.client : [];
  const chosen = clients.filter(function (c) {
    return c.client_info && c.client_info.android_client_info &&
      c.client_info.android_client_info.package_name === pkg;
  })[0] || clients[0] || {};
  const oauth = Array.isArray(chosen.oauth_client) ? chosen.oauth_client : [];
  let webClient = oauth.filter(function (c) { return c.client_type === 3; })[0];
  if (!webClient) {
    for (const client of clients) {
      const found = (client.oauth_client || []).filter(function (c) { return c.client_type === 3; })[0];
      if (found) { webClient = found; break; }
    }
  }
  const keyList = Array.isArray(chosen.api_key)
    ? chosen.api_key
    : (((chosen.client_info || {}).api_key) || []);
  const clientKeys = keyList.map(function (key) { return key.current_key; }).filter(Boolean);
  const apiKey = assertNotSecret('Firebase web API key', overrides.apiKey || clientKeys[0] || '');
  const webClientId = assertPublicId('Web client id', (webClient || {}).client_id || '');
  const region = assertPublicId('RTDB region', overrides.region || DEFAULT_RTDB_REGION);
  const databaseURL = overrides.databaseURL ||
    'https://' + projectId + '-default-rtdb.' + region + '.firebasedatabase.app';
  if (!/^https:\/\/[A-Za-z0-9._-]+$/.test(databaseURL)) {
    throw new Error('Realtime Database URL must be a plain https:// host - never relax TLS.');
  }
  return { projectId: projectId, webClientId: webClientId, apiKey: apiKey, databaseURL: databaseURL, region: region };
}

function parseArgs(argv) {
  const args = {
    in: null,
    out: 'secure_wallet_blogger_theme.xml',
    title: 'Secure Wallet',
    clientId: '',
    googleServices: path.join(__dirname, '..', '..', 'google-services.json'),
    pkg: DEFAULT_PACKAGE,
    apiKey: '',
    region: '',
    databaseURL: '',
    firebaseVersion: DEFAULT_FIREBASE_VERSION
  };
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      const value = argv[i + 1];
      if (value === undefined || value.startsWith('--')) {
        throw new Error('Missing value for ' + arg);
      }
      i += 1;
      return value;
    };
    if (arg === '--in') args.in = next();
    else if (arg === '--out') args.out = next();
    else if (arg === '--title') args.title = next();
    else if (arg === '--drive-client-id') args.clientId = next();
    else if (arg === '--google-services') args.googleServices = next();
    else if (arg === '--package') args.pkg = next();
    else if (arg === '--firebase-api-key') args.apiKey = next();
    else if (arg === '--rtdb-region') args.region = next();
    else if (arg === '--database-url') args.databaseURL = next();
    else if (arg === '--firebase-version') args.firebaseVersion = next();
    else if (arg === '--help' || arg === '-h') {
      usage();
      process.exit(0);
    } else {
      throw new Error('Unknown argument: ' + arg);
    }
  }
  return args;
}

function readEnvelope(file) {
  let text = fs.readFileSync(file, 'utf8');
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error('The export is not valid JSON: ' + error.message);
  }
  if (!parsed || typeof parsed !== 'object') throw new Error('The export is empty.');
  if (parsed.v !== 3) {
    throw new Error('Unsupported envelope version: ' + parsed.v + ' (expected 3).');
  }
  if (typeof parsed.salt !== 'string' || typeof parsed.data !== 'string') {
    throw new Error('Envelope is missing salt/data.');
  }
  const salt = parsed.salt.trim();
  const parts = parsed.data.trim().split('.');
  if (!/^[A-Za-z0-9+/=]+$/.test(salt)) throw new Error('Envelope salt is not base64.');
  if (parts.length !== 2) throw new Error('Envelope data must be "<ivB64>.<ciphertextB64>".');
  const iv = parts[0];
  const ct = parts[1];
  if (!/^[A-Za-z0-9+/=]+$/.test(iv) || !/^[A-Za-z0-9+/=]+$/.test(ct)) {
    throw new Error('Envelope ciphertext is not base64.');
  }
  const saltBytes = Buffer.from(salt, 'base64');
  const ivBytes = Buffer.from(iv, 'base64');
  const ctBytes = Buffer.from(ct, 'base64');
  if (saltBytes.length !== 16) throw new Error('Unexpected salt length: ' + saltBytes.length);
  if (ivBytes.length !== 12) throw new Error('Unexpected IV length: ' + ivBytes.length);
  if (ctBytes.length < 17) throw new Error('Ciphertext is too short.');
  return { salt, iv, ct, bytes: ctBytes.length };
}

function xmlEscape(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function jsString(value) {
  return String(value)
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'")
    .replace(/\r/g, '')
    .replace(/\n/g, ' ')
    .replace(/<\//g, '<\\/')
    .replace(/\$\{/g, '\\${')
    .replace(/`/g, '\\`');
}

function buildTheme(options) {
  const templatePath = path.join(__dirname, 'secure_wallet_blogger_theme.xml');
  const xml = fs.readFileSync(templatePath, 'utf8');
  const salt = options.envelope ? options.envelope.salt : '';
  const iv = options.envelope ? options.envelope.iv : '';
  const ct = options.envelope ? options.envelope.ct : '';
  const seedLine = "var SEED = { salt: '" + jsString(salt) + "', iv: '" + jsString(iv) +
    "', ct: '" + jsString(ct) + "', iters: 600000, clientId: '" + jsString(options.clientId || '') + "' };";
  let out = xml.replace(/var SEED = \{[^}]*\};/, () => seedLine);
  const fb = options.firebase || null;
  const clientId = String(options.clientId || (fb ? fb.webClientId : '') || '').trim();
  if (clientId) {
    out = out.replace(/var DEFAULT_CLIENT_ID = '[^']*';/, () => "var DEFAULT_CLIENT_ID = '" + jsString(clientId) + "';");
  }
  if (fb) {
    const cfgLine = "var FB_CFG = { apiKey: '" + jsString(fb.apiKey) +
      "', authDomain: '" + jsString(fb.projectId + '.firebaseapp.com') +
      "', projectId: '" + jsString(fb.projectId) +
      "', databaseURL: '" + jsString(fb.databaseURL) +
      "', webClientId: '" + jsString(fb.webClientId) + "' };";
    out = out.replace(/var FB_CFG = \{[^}]*\};/, () => cfgLine);
    const version = String(options.firebaseVersion || DEFAULT_FIREBASE_VERSION);
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('--firebase-version must look like 10.12.2');
    out = out.replace(/var FB_VERSION = '[^']*';/, () => "var FB_VERSION = '" + jsString(version) + "';");
  }
  const faces = bridgeFaces(options.palettePath || DEFAULT_PALETTE_PATH);
  out = out.replace(/var SVP_THEMES = \[[\s\S]*?\];/, () => 'var SVP_THEMES = ' + JSON.stringify(faces.themes) + ';');
  out = out.replace(/var SVP_DEFAULT_FACE = '[^']*';/, () => "var SVP_DEFAULT_FACE = '" + jsString(faces.defaultId) + "';");
  out = out.replace(/^:root\{[^\n]*\}/m, () => faces.rootCss);
  const safeTitle = jsString(xmlEscape(options.title));
  return out.replace(/<h1 id='svp-title'>[^<]*<\/h1>/, () => "<h1 id='svp-title'>" + safeTitle + "</h1>");
}

/* The 15 faces come from the app's own Palette.kt, never from a copy kept here: a theme that only
   exists on one side of the two-face pair is a bug the generator cannot ship. */
function bridgeFaces(palettePath) {
  const bridge = require('./palette-bridge');
  const bridged = bridge.bridge(palettePath);
  if (!bridged.themes.length) throw new Error('Palette.kt produced no themes to bridge');
  const ids = bridged.themes.map((theme) => theme.id);
  if (new Set(ids).size !== ids.length) throw new Error('Palette.kt lists a theme id twice: ' + ids.join(', '));
  if (!bridged.themes.some((theme) => theme.id === bridged.defaultId)) {
    throw new Error('Palette.kt defaults to "' + bridged.defaultId + '", which is not in its own list');
  }
  return bridged;
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv);
  } catch (error) {
    process.stderr.write('Error: ' + error.message + '\n\n');
    usage();
    process.exit(1);
  }

  let envelope = null;
  if (args.in) {
    try {
      envelope = readEnvelope(args.in);
    } catch (error) {
      process.stderr.write('Error: ' + error.message + '\n');
      process.exit(1);
    }
  }

  let firebase = null;
  try {
    firebase = readFirebaseConfig(args.googleServices, args.pkg, {
      apiKey: (args.apiKey || '').trim(),
      region: (args.region || '').trim(),
      databaseURL: (args.databaseURL || '').trim()
    });
  } catch (error) {
    process.stderr.write('Error: ' + error.message + '\n');
    process.exit(1);
  }

  const xml = buildTheme({
    title: args.title || 'Secure Wallet',
    clientId: (args.clientId || '').trim(),
    firebaseVersion: args.firebaseVersion,
    envelope,
    firebase
  });

  const outPath = path.resolve(process.cwd(), args.out);
  const templatePath = path.join(__dirname, 'secure_wallet_blogger_theme.xml');
  if (outPath === path.resolve(templatePath)) {
    throw new Error('Refusing to write onto the template itself (' + templatePath + '). ' +
      'The template is the source of truth and must stay free of any injected project values; ' +
      'pass --out <upload copy>. The bundled app/src/main/assets/blogger_theme.xml is a generated ' +
      'file - regenerate it with the same options so a theme exported on the phone carries the ' +
      'project web key too.');
  }
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, xml, 'utf8');

  const sizeKb = (Buffer.byteLength(xml, 'utf8') / 1024).toFixed(1);
  process.stdout.write('Blogger theme written: ' + outPath + '\n');
  process.stdout.write('  title: ' + (args.title || 'Secure Wallet') + '\n');
  process.stdout.write('  snapshot: ' + (envelope ? 'embedded (' + envelope.bytes + ' ciphertext bytes)' : 'none (unlock by picking a file or Drive)') + '\n');
  process.stdout.write('  realtime: project ' + (firebase ? firebase.projectId : 'template default') +
    ' · ' + (firebase ? firebase.databaseURL : 'template default') + '\n');
  process.stdout.write('  web client id: ' + (args.clientId ? 'custom override' : firebase ? 'from google-services.json' : 'app project default from template') + '\n');
  process.stdout.write('  firebase api key in theme: ' + (firebase && firebase.apiKey ? 'yes (public web key, from google-services.json)' : 'no') + '\n');
  process.stdout.write('  size: ' + sizeKb + ' KB\n');
  process.stdout.write('\nUpload it in Blogger: Theme -> Customise -> Edit HTML -> replace everything -> Save.\n');
}

main();
