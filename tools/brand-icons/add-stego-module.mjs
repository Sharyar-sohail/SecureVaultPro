#!/usr/bin/env node
/*
 * Adds the Secret Image (steganography) board module to the shipped icon family.
 *
 * Why this exists next to build.mjs: the generator in this tree has moved on to a newer plate
 * grammar set than the 308 drawables the app actually ships (its catalog template even imports
 * an IconGrammar enum the theme layer does not have yet). Re-running it would repaint every
 * theme's board, which cannot be verified from here. So the new module is cut from each theme's
 * OWN shipped sibling — same plate, same ink, same scale, only the glyph and the plate colour are
 * new — which keeps one coherent family per theme, exactly the rule build.mjs enforces.
 *
 * slate is the exception: its family is PIXEL_SPRITE, where the glyph is a grid of coverage cells
 * rather than one path. Its cells come from a generator run, harvested into .stego_harvest/.
 *
 * Run: node tools/brand-icons/add-stego-module.mjs
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const drawableDir = join(root, 'app/src/main/res/drawable');
const catalogPath = join(root, 'app/src/main/java/com/securevault/pro/presentation/components/BrandIconCatalog.kt');
const harvest = join(root, '.stego_harvest/ic_b_slate_stego.xml');
const glyphSvg = join(here, 'vendor/regular/image.svg');
const MODULE = 'STEGO';
const SLUG = 'stego';
const LABEL = 'Secret Image';
const TEMPLATE_MODULE = 'files';

const phosphorPath = (() => {
    const svg = readFileSync(glyphSvg, 'utf8');
    const match = svg.match(/<path[^>]*\sd="([^"]+)"/);
    if (!match) throw new Error(`${glyphSvg} carries no <path d="...">`);
    return match[1].trim();
})();

/* --------------------------------- colour maths ---------------------------------- */
function hexToHsl(hex) {
    const n = parseInt(hex.slice(1), 16);
    const r = ((n >> 16) & 255) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    let h = 0;
    const l = (max + min) / 2;
    const s = max === min ? 0 : (max - min) / (1 - Math.abs(2 * l - 1));
    if (max !== min) {
        if (max === r) h = ((g - b) / (max - min)) % 6;
        else if (max === g) h = (b - r) / (max - min) + 2;
        else h = (r - g) / (max - min) + 4;
    }
    return { h: (h * 60 + 360) % 360, s, l };
}

function hslToHex({ h, s, l }) {
    const c = (1 - Math.abs(2 * l - 1)) * s;
    const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    const m = l - c / 2;
    const zone = [[c, x, 0], [x, c, 0], [0, c, x], [0, x, c], [x, 0, c], [c, 0, x]][Math.floor(((h % 360) / 60)) % 6];
    const to = (v) => Math.round((v + m) * 255).toString(16).padStart(2, '0');
    return `#${to(zone[0])}${to(zone[1])}${to(zone[2])}`.toUpperCase();
}

function luminance(hex) {
    const n = parseInt(hex.slice(1), 16);
    const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

const ratioAgainstWhite = (hex) => 1.05 / (luminance(hex) + 0.05);

/** Hue-shift clear of the sibling ramps, then darken until the white ink on the plate is readable. */
function stegoPlate(toolsHex) {
    const base = hexToHsl(toolsHex);
    let candidate = hslToHex({ h: (base.h + 42) % 360, s: Math.max(base.s, 0.45), l: base.l });
    let guard = 0;
    while (ratioAgainstWhite(candidate) < 4.6 && guard++ < 40) {
        const hsl = hexToHsl(candidate);
        candidate = hslToHex({ ...hsl, l: Math.max(0.06, hsl.l - 0.02) });
    }
    return { hex: candidate, ratio: ratioAgainstWhite(candidate) };
}

/* --------------------------------- drawables ---------------------------------- */
function buildFromSibling(themeId, plateHex) {
    const source = join(drawableDir, `ic_b_${themeId}_${TEMPLATE_MODULE}.xml`);
    if (!existsSync(source)) throw new Error(`${themeId}: no ${TEMPLATE_MODULE} sibling to inherit`);
    const text = readFileSync(source, 'utf8');

    // Re-plate: the sibling's own plate fill, whatever shape that theme's family draws.
    const platePainted = text.replace(
        /(<path[^>]*android:fillColor=")#([0-9A-Fa-f]{6,8})(")/,
        `$1#FF${plateHex.slice(1)}$3`
    );
    if (platePainted === text) throw new Error(`${themeId}: no plate fill found in the sibling`);
    if (!/<vector[\s\S]*"#[0-9A-F]{8}"/.test(platePainted)) {
        throw new Error(`${themeId}: recoloured plate is not a quoted colour`);
    }

    const glyphTag = `<path\n                android:pathData="${phosphorPath}"\n                android:fillColor="#FFFFFFFF"\n            />`;
    const groupBlock = /<group([\s\S]*?)>([\s\S]*?)<\/group>/;
    if (groupBlock.test(platePainted)) {
        // Every path inside the group is glyph art (some modules carry two), so the whole
        // interior is replaced rather than one line of it.
        const built = platePainted.replace(groupBlock, (_all, attrs) => `<group${attrs}>${glyphTag}</group>`);
        return stamp(themeId, built);
    }

    // slate: the glyph is a field of coverage cells. Take the harvested cells, keep this
    // theme's own plate and shadow rows, and drop the sibling's cells.
    const pixels = readFileSync(harvest, 'utf8');
    const isInk = (pathTag) => /android:fillColor="#[0-9A-F]{2}FFFFFF"/.test(pathTag);
    const cells = (pixels.match(/<path[^>]*\/>/g) || []).filter(isInk);
    if (cells.length === 0) throw new Error(`${themeId}: harvest carried no ink cells`);
    const stripped = platePainted
        .split('\n')
        .filter((line) => !(isInk(line) && line.includes('<path')))
        .join('\n');
    const shadowAnchor = stripped.lastIndexOf('</vector>');
    const built = `${stripped.slice(0, shadowAnchor)}${cells.map((c) => `    ${c}\n`).join('')}${stripped.slice(shadowAnchor)}`;
    return stamp(themeId, built);
}

function stamp(themeId, body) {
    return body.replace(
        /<!--[\s\S]*?-->/,
        `<!-- Generated by tools/brand-icons/add-stego-module.mjs from the Phosphor (MIT) pack.\n     Theme: ${themeId} | Glyph: image | Module: ${LABEL} -->`
    );
}

/* --------------------------------- catalog ---------------------------------- */
/**
 * Line-based, because the two maps differ in their trailing commas: every module line in both
 * maps ends with one (HOME is the last entry), so the new line is spliced in after TOOLS with the
 * same indentation instead of being regex-welded onto the anchor.
 */
function addToCatalog(plates) {
    const lines = readFileSync(catalogPath, 'utf8').split('\n');
    const enumIndex = lines.findIndex((l) => l.startsWith('    HOME("home", "Home"),'));
    if (enumIndex < 0) throw new Error('catalog enum anchor missing');
    if (lines.join('\n').includes('VaultModule.STEGO')) throw new Error('catalog already carries STEGO');
    lines.splice(enumIndex, 0, `    ${MODULE}("${SLUG}", "${LABEL}"),`);

    const out = [];
    let theme = null;
    let added = 0;
    for (const line of lines) {
        out.push(line);
        const block = line.match(/^\s*"(\w+)" to mapOf\(/);
        if (block) { theme = block[1]; continue; }
        if (!theme || !plates[theme]) continue;
        const tools = line.match(/^(\s*)VaultModule\.TOOLS to (R\.drawable\.ic_b_\w+|0xFF[0-9A-F]{6}uL)(,?)$/);
        if (!tools) continue;
        const value = tools[2].startsWith('R.')
            ? `R.drawable.ic_b_${theme}_${SLUG}`
            : `0xFF${plates[theme].slice(1)}uL`;
        out.push(`${tools[1]}VaultModule.${MODULE} to ${value},`);
        added++;
        if (tools[3] !== ',') throw new Error(`${theme}: TOOLS line carries no comma — unexpected formatting`);
    }
    if (added !== Object.keys(plates).length * 2) {
        throw new Error(`expected ${Object.keys(plates).length * 2} insertions, made ${added}`);
    }
    writeFileSync(catalogPath, out.join('\n'));
}

function toolsAccents() {
    const text = readFileSync(catalogPath, 'utf8');
    const found = {};
    const re = /"(\w+)" to mapOf\([\s\S]*?VaultModule\.TOOLS to 0xFF([0-9A-F]{6})uL/g;
    let m;
    while ((m = re.exec(text)) !== null) if (!found[m[1]]) found[m[1]] = `#${m[2]}`;
    return found;
}

const accents = toolsAccents();
const plates = {};
for (const [themeId, toolsHex] of Object.entries(accents)) {
    const { hex, ratio } = stegoPlate(toolsHex);
    plates[themeId] = hex;
    writeFileSync(join(drawableDir, `ic_b_${themeId}_${SLUG}.xml`), buildFromSibling(themeId, hex));
    console.log(`  ${themeId.padEnd(8)} plate ${hex} white-ink ${ratio.toFixed(2)}:1`);
}
addToCatalog(plates);
console.log(`stego module added to ${Object.keys(plates).length} themes and the catalog`);
