'use strict';
/*
 * palette-bridge.js — reads the app's own VaultPalettes list and emits the token set the web
 * companion paints with.
 *
 * The two faces have to be the same coin, so the web must not keep its own copy of the colours:
 * it derives them from Palette.kt. Adding, renaming or re-colouring a theme in the app changes the
 * generated XML on the next run, and a mismatch is a build-time failure instead of a owner noticing
 * that the browser looks nothing like the phone.
 */

const fs = require('fs');
const path = require('path');

const COLOR_KEYS = {
  abyssDark: 'bg',
  abyssSurface: 'panel',
  abyssElevated: 'panel2',
  cyan: 'accent',
  amber: 'gold',
  indigo: 'link',
  mint: 'good',
  coral: 'bad',
  lavender: 'soft',
  textPrimary: 'text',
  textSecondary: 'text2',
  textMuted: 'muted',
};

/** Compose Color(0xAARRGGBB) -> CSS, keeping the alpha the theme author chose. */
function toCss(argb) {
  const n = BigInt(argb);
  const a = Number((n >> 24n) & 0xffn);
  const r = Number((n >> 16n) & 0xffn);
  const g = Number((n >> 8n) & 0xffn);
  const b = Number(n & 0xffn);
  const hex = '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');
  return a === 255 ? hex : `rgba(${r},${g},${b},${(a / 255).toFixed(3)})`;
}

function field(body, name) {
  const m = body.match(new RegExp('(?:^|\\n)\\s*' + name + '\\s*=\\s*([^,\\n]+)', 'm'));
  return m ? m[1].trim() : null;
}

function parsePaletteKt(source) {
  const start = source.indexOf('val all: List<VaultPalette> = listOf(');
  if (start < 0) throw new Error('Palette.kt has no VaultPalettes.all list to bridge from');
  const list = source.slice(start);
  const themes = [];
  const marker = /VaultPalette\(\s*\n\s*id = "([a-z0-9_]+)"/g;
  const hits = [];
  let m;
  while ((m = marker.exec(list)) !== null) hits.push({ id: m[1], at: m.index });
  hits.forEach((hit, i) => {
    const end = i + 1 < hits.length ? list.lastIndexOf('VaultPalette(', hits[i + 1].at + start * 0) : list.length;
    const body = list.slice(hit.at, i + 1 < hits.length ? hits[i + 1].at : list.length);
    const theme = { id: hit.id, tokens: {}, flags: {} };
    const name = field(body, 'name');
    if (name) theme.name = name.replace(/^"|"$/g, '');
    const light = field(body, 'isLight');
    theme.isLight = light === 'true';
    for (const [kotlinKey, cssKey] of Object.entries(COLOR_KEYS)) {
      const raw = body.match(new RegExp(kotlinKey + '\\s*=\\s*Color\\((0x[0-9a-fA-F]+)\\)'));
      if (raw) theme.tokens[cssKey] = toCss(BigInt(raw[1]));
    }
    for (const flag of ['hardOutline', 'gradientButtons', 'glow', 'uppercaseLabels', 'monoTags', 'cardGradient', 'sheen', 'navFloating']) {
      const v = field(body, flag);
      if (v !== null) theme.flags[flag] = v === 'true';
    }
    for (const token of ['layout', 'backdrop', 'header', 'dock', 'pieForm', 'iconStyle', 'design']) {
      const v = field(body, token);
      if (v) theme.flags[token] = (v || '').split('.').pop();
    }
    const radius = (k) => {
      const v = field(body, k);
      return v ? parseFloat(v) : null;
    };
    theme.flags.cardCorner = radius('cardCorner');
    theme.flags.buttonCorner = radius('buttonCorner');
    theme.flags.inputCorner = radius('inputCorner');
    theme.flags.chipCorner = radius('chipCorner');
    theme.flags.navHeight = radius('navHeight');
    theme.flags.pieInnerRatio = (() => { const v = field(body, 'pieInnerRatio'); return v ? parseFloat(v) : null; })();
    void end;
    themes.push(theme);
  });
  return themes;
}

const LAYOUT_COLUMNS = {
  CLASSIC: 'repeat(4,minmax(0,1fr))',
  BENTO: 'repeat(4,minmax(0,1fr))',
  AGENTIC: 'repeat(2,minmax(0,1fr))',
  ADAPTIVE: 'repeat(2,minmax(0,1fr))',
  GENERATIVE: 'repeat(3,minmax(0,1fr))',
  MOSAIC: 'repeat(3,minmax(0,1fr))',
  LEDGER: 'repeat(2,minmax(0,1fr))',
  DUO: 'repeat(2,minmax(0,1fr))',
  COMPACT: 'repeat(5,minmax(0,1fr))',
  DENSE: 'repeat(6,minmax(0,1fr))',
  COLUMN: 'repeat(3,minmax(0,1fr))',
  SPLIT: 'repeat(2,minmax(0,1fr))',
  GALLERY: 'repeat(3,minmax(0,1fr))',
  STRIP: 'repeat(4,minmax(0,1fr))',
  TRIAD: 'repeat(2,minmax(0,1fr))',
};

function layoutColumns(layout) {
  return LAYOUT_COLUMNS[layout] || 'repeat(auto-fill,minmax(150px,1fr))';
}

/**
 * The static `:root` fallback, in the variable names the skin already reads.
 *
 * The face layer paints from JavaScript, but the first frame is CSS-only, so the fallback has to be
 * the app's default face rather than a colour chosen by hand here. Injecting it at build time is what
 * keeps "Aqua Tint" one fact instead of two.
 */
function rootFallbackCss(theme) {
  const t = theme.tokens;
  const f = theme.flags;
  const rows = [
    `--bg:${t.bg}`, `--panel:${t.panel}`, `--panel2:${t.panel2}`,
    `--line:${f.hardOutline ? t.text : 'rgba(122,180,255,.16)'}`,
    `--cyan:${t.accent}`, `--mint:${t.good}`, `--indigo:${t.link}`,
    `--amber:${t.gold}`, `--coral:${t.bad}`, `--lavender:${t.soft}`,
    `--text:${t.text}`, `--text2:${t.text2}`, `--muted:${t.muted}`,
    `--on-accent:#04121f`,
    `--card-r:${num(f.cardCorner, 16)}px`, `--btn-r:${num(f.buttonCorner, 12)}px`,
    `--input-r:${num(f.inputCorner, 12)}px`, `--chip-r:${num(f.chipCorner, 999)}px`,
    `--card-border:${f.hardOutline ? 2 : 1}px`,
    `--card-shadow:${f.glow ? '0 18px 46px rgba(0,0,0,.32)' : '0 8px 22px rgba(0,0,0,.32)'}`,
    `--label-transform:${f.uppercaseLabels ? 'uppercase' : 'none'}`,
    `--btn-fill:linear-gradient(135deg,${t.accent},${t.good})`,
    `--grid-cols:${layoutColumns(f.layout)}`,
    `--face-blur:${f.sheen ? '18px' : '0px'}`,
  ];
  return ':root{' + rows.join(';') + '}';
}

function num(value, fallback) {
  return typeof value === 'number' && isFinite(value) ? value : fallback;
}

function bridge(palettePath) {
  const source = fs.readFileSync(palettePath, 'utf8');
  const themes = parsePaletteKt(source);
  const defaultId = (source.match(/const val DEFAULT_ID = "([a-z0-9_]+)"/) || [])[1] || 'aqua';
  const fallback = themes.find((theme) => theme.id === defaultId) || themes[0];
  return { themes, defaultId, rootCss: rootFallbackCss(fallback) };
}

module.exports = { bridge, parsePaletteKt, rootFallbackCss, toCss, COLOR_KEYS, LAYOUT_COLUMNS };

if (require.main === module) {
  const palette = path.resolve(__dirname, '../../app/src/main/java/com/securevault/pro/presentation/theme/Palette.kt');
  const out = bridge(palette);
  console.log(JSON.stringify(out.themes.map((t) => ({ id: t.id, name: t.name, isLight: t.isLight, bg: t.tokens.bg, accent: t.tokens.accent, layout: t.flags.layout })), null, 2));
  console.log('default id:', out.defaultId, '| themes:', out.themes.length);
}
