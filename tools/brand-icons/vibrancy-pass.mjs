/**
 * VIBRANCY PASS — the owner's rule: "Force the SHARP, VIBRANT, high-contrast color rule on ALL
 * themes. No dim colors allowed."
 *
 * The shipped plates were cut by build.mjs's contrast guard, which darkens a colour until white
 * ink clears AA and stops there. Darkening is not saturating, so brand ramps that start muted
 * landed on grey-brown plates (#747479 Tools, #867148 Passwords): AA-clean, and visibly dim.
 *
 * This pass re-cuts every plate on two rules, in order:
 *   1. HSL saturation at or above CHROMA_FLOOR, on the plate's own hue and lightness;
 *   2. white glyph ink at or above AA_PLATE, reached by lowering lightness only.
 *
 * It writes the same result into the drawable (the plate path's fillColor, which is what the eye
 * sees) and into BrandIconCatalog's accent map (which is what the board's tint, rules and count
 * ink are derived from). Those two used to disagree for 52 of the 210 plates; a theme's accent is
 * now literally the colour its icon draws.
 *
 * Run: node tools/brand-icons/vibrancy-pass.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const catalogPath = path.join(root, 'app/src/main/java/com/securevault/pro/presentation/components/BrandIconCatalog.kt');
const drawableDir = path.join(root, 'app/src/main/res/drawable');

const CHROMA_FLOOR = 0.62;
const AA_PLATE = 4.6;
const NEUTRAL = new Set(['FF000000', 'FFFFFFFF']);

const hexToRgb = (hex) => [parseInt(hex.slice(0, 2), 16) / 255, parseInt(hex.slice(2, 4), 16) / 255, parseInt(hex.slice(4, 6), 16) / 255];
const rgbToHex = (rgb) => rgb.map((v) => Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).toUpperCase().padStart(2, '0')).join('');

function toHsl([r, g, b]) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b), delta = max - min;
  const l = (max + min) / 2;
  if (delta === 0) return { h: 0, s: 0, l };
  const s = delta / (1 - Math.abs(2 * l - 1));
  let h;
  if (max === r) h = ((g - b) / delta) % 6;
  else if (max === g) h = (b - r) / delta + 2;
  else h = (r - g) / delta + 4;
  return { h: (h * 60 + 360) % 360, s, l };
}

function fromHsl({ h, s, l }) {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  const [r1, g1, b1] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x]
    : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [r1 + m, g1 + m, b1 + m];
}

const luminance = (rgb) => rgb.map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
  .reduce((sum, channel, index) => sum + [0.2126, 0.7152, 0.0722][index] * channel, 0);
const whiteRatio = (hex) => 1.05 / (luminance(hexToRgb(hex)) + 0.05);

/** Saturated, then dark only as far as the white glyph needs. */
function vividPlate(hex) {
  const hsl = toHsl(hexToRgb(hex));
  const tuned = fromHsl({ ...hsl, s: Math.max(hsl.s, CHROMA_FLOOR) });
  let rgb = tuned, l = hsl.l, guard = 0;
  while (whiteRatio(rgbToHex(rgb)) < AA_PLATE && l > 0.05 && guard++ < 80) {
    l -= 0.01;
    rgb = fromHsl({ h: hsl.h, s: Math.max(hsl.s, CHROMA_FLOOR), l });
  }
  return rgbToHex(rgb);
}

const catalog = fs.readFileSync(catalogPath, 'utf8');
const lines = catalog.split('\n');

// "id" to mapOf( … ) blocks, in file order, from each map's own indent.
function blockStart(marker) {
  const index = lines.findIndex((line) => line.includes(marker));
  if (index < 0) throw new Error(`catalog is missing ${marker}`);
  return index;
}
const iconsFrom = blockStart('private val icons');
const accentsFrom = blockStart('private val accents');

function readMaps(from, to, parse) {
  const maps = {};
  let theme = null;
  for (let i = from; i < to; i++) {
    const open = lines[i].match(/^\s*"([a-z0-9]+)" to mapOf\(/);
    if (open) { theme = open[1]; maps[theme] = {}; continue; }
    if (theme) {
      const entry = parse(lines[i]);
      if (entry) maps[theme][entry.module] = entry.value;
    }
  }
  return maps;
}
const icons = readMaps(iconsFrom, accentsFrom, (line) => {
  const m = line.match(/VaultModule\.([A-Z_0-9]+) to R\.drawable\.ic_b_[a-z0-9]+_(\w+)/);
  return m ? { module: m[1], value: m[2] } : null;
});
// The accents map runs to the end of the object body; the closing brace of the last theme is
// the first line that is only whitespace + ")", so scan to the file end and stop re-keying
// once a line opens a new non-map block.
const accents = readMaps(accentsFrom, lines.length, (line) => {
  const m = line.match(/VaultModule\.([A-Z_0-9]+) to 0xFF([0-9A-F]{6})uL/);
  return m ? { module: m[1], value: m[2] } : null;
});

let repainted = 0, synced = 0, rewritten = 0;
const report = [];

for (const [theme, modules] of Object.entries(icons)) {
  const accentsOf = accents[theme];
  if (!accentsOf) throw new Error(`no accent map for ${theme}`);
  for (const [module, key] of Object.entries(modules)) {
    const file = path.join(drawableDir, `ic_b_${theme}_${key}.xml`);
    if (!fs.existsSync(file)) throw new Error(`missing drawable ${file}`);
    const xml = fs.readFileSync(file, 'utf8');
    const opaque = [...xml.matchAll(/android:fillColor="#FF([0-9A-F]{6})"/gi)].map((m) => m[1].toUpperCase());
    const drawn = opaque.find((hex) => !NEUTRAL.has('FF' + hex));
    if (!drawn) throw new Error(`${file}: no opaque coloured plate fill found`);

    const next = vividPlate(drawn);
    if (next !== drawn) {
      fs.writeFileSync(file, xml.replaceAll(`#FF${drawn}`, `#FF${next}`));
      repainted++;
    }
    const oldAccent = accentsOf[module];
    if (oldAccent !== next) synced++;
    if (oldAccent !== drawn) report.push(`${theme}/${module}: catalog ${oldAccent} != drawn ${drawn} -> ${next}`);

    // Rewrite this theme's accent line for this module, in place.
    let done = false;
    let inTheme = false;
    for (let i = accentsFrom; i < lines.length && !done; i++) {
      const open = lines[i].match(/^\s*"([a-z0-9]+)" to mapOf\(/);
      if (open) { inTheme = open[1] === theme; continue; }
      if (!inTheme) continue;
      if (lines[i].includes(`VaultModule.${module} to`)) {
        lines[i] = lines[i].replace(/0xFF[0-9A-F]{6}uL/, `0xFF${next}uL`);
        done = true;
        rewritten++;
      }
    }
    if (!done) throw new Error(`catalog: no accent line for ${theme}/${module}`);
  }
}

fs.writeFileSync(catalogPath, lines.join('\n'));
console.log(`plates repainted: ${repainted}, accents re-pointed at the drawn plate: ${synced}, catalog lines rewritten: ${rewritten}`);
console.log(report.slice(0, 8).join('\n'));

// Proof of the two rules, straight off the files that were just written.
let worstChroma = 1, worstRatio = 99;
for (const file of fs.readdirSync(drawableDir).filter((f) => f.startsWith('ic_b_'))) {
  const xml = fs.readFileSync(path.join(drawableDir, file), 'utf8');
  const opaque = [...xml.matchAll(/android:fillColor="#FF([0-9A-F]{6})"/gi)].map((m) => m[1].toUpperCase());
  const drawn = opaque.find((hex) => !NEUTRAL.has('FF' + hex));
  if (!drawn) continue;
  const rgb = hexToRgb(drawn);
  const { s } = toHsl(rgb);
  worstChroma = Math.min(worstChroma, s);
  worstRatio = Math.min(worstRatio, whiteRatio(drawn));
}
console.log(`lowest plate chroma now ${worstChroma.toFixed(2)}, lowest white-ink ratio ${worstRatio.toFixed(2)}:1`);
