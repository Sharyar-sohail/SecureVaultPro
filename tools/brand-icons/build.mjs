#!/usr/bin/env node
/**
 * Brand icon pack builder — CLI half.
 *
 * The fifteen construction grammars live in `icon-grammars.mjs` (pure: geometry, layer model,
 * contrast maths, importable by `node --test`). This file owns the side effects: loading the
 * vendored Phosphor art, rasterising it for the two cell-rebuild grammars, emitting the
 * Android VectorDrawables, the generated BrandIconCatalog.kt, README and grammar-report.json.
 *
 * Run: `node build.mjs` (no arguments; offline-first — it only touches the network to refresh
 * the MIT license text and to vendor a glyph that is not on disk yet).
 *
 * Output: app/src/main/res/drawable/ic_b_<theme>_<module>.xml
 *         app/src/main/java/.../presentation/components/BrandIconCatalog.kt
 *         tools/brand-icons/grammar-report.json   (the numbers the handoff quotes)
 */

import { mkdirSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

import {
    GRAMMARS, BUILDERS, needsTones, verifyGrammar, enforceStackContrast, governingStack,
    contrastRatio, compositeOver, scaleHue,
    INK_WHITE, AA_PLATE_TEXT, PLATE_GUARD
} from './icon-grammars.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const vendorDir = join(here, 'vendor');
const drawableDir = join(repoRoot, 'app', 'src', 'main', 'res', 'drawable');
const catalogOut = join(
    repoRoot, 'app', 'src', 'main', 'java', 'com', 'securevault', 'pro',
    'presentation', 'components', 'BrandIconCatalog.kt'
);
const reportOut = join(here, 'grammar-report.json');
const PHOSPHOR_CDN = 'https://cdn.jsdelivr.net/npm/@phosphor-icons/core@2/assets';

/* resvg is only needed by the two raster grammars; loaded lazily so a missing native
 * binary cannot take the other thirteen families down with it. */
let resvgCtor = null;
function loadResvg() {
    if (resvgCtor) return resvgCtor;
    const require = createRequire(import.meta.url);
    resvgCtor = require('@resvg/resvg-js').Resvg;
    return resvgCtor;
}

const MODULES = [
    ['PASSWORDS', 'passwords', 'Passwords', 'password'],
    ['TWO_FA', 'twofa', '2FA', 'shield-check'],
    ['CARDS', 'cards', 'Cards', 'credit-card'],
    ['CRYPTO', 'crypto', 'Crypto', 'coins'],
    ['NOTES', 'notes', 'Notes', 'note'],
    ['CONTACTS', 'contacts', 'Contacts', 'address-book'],
    ['FILES', 'files', 'Files', 'folder-open'],
    ['SCAN', 'scan', 'Scan', 'qr-code'],
    ['BACKUP', 'backup', 'Backup', 'cloud-arrow-down'],
    ['HEALTH', 'health', 'Health', 'pulse'],
    ['SYNC', 'sync', 'Sync', 'arrows-clockwise'],
    ['TOOLS', 'tools', 'Tools', 'sliders-horizontal'],
    // Stego rides the board: hiding text in a photo is a module, not a setting.
    ['STEGO', 'stego', 'Secret Image', 'image'],
    // Dock-only glyph; the board above is what the home grid renders.
    ['HOME', 'home', 'Home', 'house']
];

/**
 * Fifteen themes, fifteen ids that already ship. Only `name` and the geometry move — the id is
 * the persisted key in DataStore and in the sync payload, so it is frozen. The retired-id
 * migration table lives with it in the theme layer (VaultPalettes.legacyAliases); this table
 * deliberately keeps every shipped id.
 */
const THEMES = [
    { id: 'ios', name: 'iOS Glass', grammar: 'GLASS_SILVERWARE', inspiredBy: 'Apple iOS',
        colors: ['#0A84FF', '#30D158', '#5E5CE6', '#FF9F0A', '#FFD60A', '#FF2D92',
            '#64D2FF', '#BF5AF2', '#FF3B30', '#00C7BE', '#FF6B22', '#8E8E93'], home: '#0A84FF' },
    { id: 'oneui', name: 'Samsung One UI', grammar: 'COMFORT_SHOULDER', inspiredBy: 'Samsung One UI',
        colors: ['#1B6EF3', '#14B87A', '#6A5BF7', '#E88B00', '#0A9CE5', '#9C6ADE',
            '#23B473', '#F26A21', '#E64545', '#00B2A8', '#5A6FE8', '#6AA500'], home: '#1B6EF3' },
    { id: 'arctic', name: 'Neumorphic Soft', grammar: 'NEUMORPH_EXTRUDE', inspiredBy: 'Neumorphism',
        // Neumorphism is monochrome: lit slate-blues deep enough to hold white ink.
        colors: ['#4E6478', '#3E5C74', '#5A6E82', '#2F4B63', '#61748A', '#47607A',
            '#3A5468', '#6A7E94', '#33566E', '#55697F', '#2C4560', '#5F7488'], home: '#4E6478' },
    { id: 'voltra', name: 'Cyberpunk Neon', grammar: 'NEON_CHAMFER', inspiredBy: 'Arcade night',
        colors: ['#C9F23D', '#23E3FF', '#38F2A0', '#FFD400', '#B14DFF', '#FF3D71',
            '#A8D62F', '#1FC9E6', '#2ED68C', '#E6BC00', '#9A3DE0', '#E63563'], home: '#C9F23D' },
    { id: 'terra', name: 'Claymorphism', grammar: 'CLAY_PUFF', inspiredBy: 'Kiln pottery',
        colors: ['#A85433', '#3E6B55', '#7A9E5E', '#D98A4E', '#C08A8A', '#C65B4C',
            '#8F4A2E', '#4E7B62', '#6B8F52', '#C1763E', '#A87878', '#B04E40'], home: '#A85433' },
    { id: 'slate', name: 'Retro 8-Bit', grammar: 'PIXEL_SPRITE', inspiredBy: 'Cartridge game',
        // Cartridge palette: saturated, but sunk to the tone a white sprite reads on.
        colors: ['#C21807', '#1F6FB2', '#2C7A3F', '#B36B00', '#7A3FB8', '#C05A1E',
            '#0F8A8A', '#8A1F6E', '#4B5E13', '#A02020', '#1F4FA8', '#9A6A00'], home: '#C21807' },
    { id: 'royal', name: 'Liquid Metal', grammar: 'CHROME_LENS', inspiredBy: 'Chrome & velvet',
        colors: ['#A78BFA', '#6C5CE7', '#3ED6A4', '#D9B878', '#C4B5FD', '#F0557A',
            '#8F70E6', '#5A4AD1', '#32C08F', '#C4A05C', '#B3A0F0', '#D6466A'], home: '#A78BFA' },
    { id: 'light', name: 'Minimal Line', grammar: 'MONOLINE_RING', inspiredBy: 'Material You',
        weight: 'thin', // the one family that ships as single-weight line art
        colors: ['#6750A4', '#1E8E3E', '#1A73E8', '#B06000', '#00897B', '#A02080',
            '#00779B', '#6B8E00', '#C5221F', '#4B3DD6', '#D65A00', '#5F6368'], home: '#6750A4' },
    { id: 'sunset', name: 'Gradient Mesh', grammar: 'MESH_BLOB', inspiredBy: 'Mesh gradient',
        colors: ['#FF8A5C', '#6A5AC0', '#5BCF9B', '#FFB35C', '#C99BE0', '#FF6E7F',
            '#E76F51', '#8B7BD8', '#4BBE8C', '#E89A4E', '#B98AE0', '#FF97A5'], home: '#FF8A5C',
        tone: ['#FF5C8A', '#FFB86B'] },
    { id: 'sakura', name: 'Origami', grammar: 'ORIGAMI_FOLD', inspiredBy: 'Paper folding',
        colors: ['#B84A78', '#6C5CE0', '#58A574', '#D69345', '#B18AE0', '#D95555',
            '#A84470', '#5A4AC8', '#4E9668', '#C4842E', '#9A6FC8', '#C04A4A'], home: '#B84A78' },
    { id: 'carbon', name: 'Bento Grid', grammar: 'BENTO_SUBBOX', inspiredBy: 'Bento box',
        colors: ['#FF7A18', '#6FA8DC', '#3CB073', '#D9A232', '#7A88B6', '#E04848',
            '#E0640E', '#5787B8', '#4FD08A', '#FFC246', '#9AA8D6', '#FF5C5C'], home: '#FF7A18' },
    { id: 'noir', name: 'Dark Academia', grammar: 'ENGRAVED_PLATE', inspiredBy: 'Print editorial',
        colors: ['#C8A96B', '#8FA3C8', '#A3BE8C', '#D9A45B', '#B7AEC9', '#C96F5E',
            '#9C8452', '#6E7FA0', '#88A06E', '#C08C45', '#A293B4', '#8F7A6B'], home: '#C8A96B' },
    { id: 'mint', name: 'Vibrant Duotone', grammar: 'DUOTONE_SPLIT', inspiredBy: 'Duotone print',
        colors: ['#0C7C70', '#4B6BCB', '#2FA36B', '#E3A23C', '#8A7FBF', '#D6604A',
            '#0E8A7C', '#3E7BC4', '#5FBF8A', '#C98A22', '#7A6FB0', '#4A9E93'], home: '#0C7C70',
        // Second tone of the split, also required to hold white ink.
        tone: ['#2B4C8F', '#8F3D6B', '#1F6B52', '#8A5A1F', '#3D4C8F', '#8F2B3D',
            '#1F6B6B', '#6B3D8F', '#2B6B3D', '#8F5B2B', '#4A3D8F', '#3D5A6B'] },
    { id: 'aqua', name: 'Aurora', grammar: 'WAVE_FIELD', inspiredBy: 'Polar light',
        colors: ['#2ED3B7', '#5EE6A0', '#6C8CFF', '#FFB44D', '#33C4FF', '#B08CFF',
            '#FF6F8B', '#00E5A8', '#4DA3FF', '#F5C97B', '#FF9EB1', '#8FF3D6'], home: '#2ED3B7',
        tone: ['#4DE3C0', '#6C8CFF'] },
    { id: 'dark', name: 'Emulate OS Deep Dark', grammar: 'OLED_NOTCH', inspiredBy: 'OLED black',
        colors: ['#9C7CFF', '#34C775', '#5B9CFF', '#D98A1F', '#2BC4D4', '#D96BC4',
            '#35B6E0', '#8FBF1F', '#F2555A', '#7C7BFF', '#E86FA4', '#9AA0A6'], home: '#9C7CFF' }
];

/* Ids that no longer ship; their drawables are pruned on every build. The user-facing
 * migration for saved preferences is VaultPalettes.legacyAliases in the theme layer. */
const DEFAULT_THEME = 'aqua';
const RETIRED_THEMES = ['lumen', 'hyper', 'funtouch', 'volt'];

/* ===========================================================================
 * PLATE PREP — correct each ramp against the stack its grammar really lands on
 * ======================================================================== */
const probeCtx = { gridFor: (box, n) => Array.from({ length: n }, () => Array(n).fill(0)) };

function tonesFor(theme) {
    if (!needsTones(theme)) return ['#707070', '#606060'];
    if (theme.tone && theme.tone.length >= 2) return [theme.tone[0], theme.tone[1]];
    // Derived fallback: the plate pushed up a hue step and down a tone step.
    return [scaleHue(theme.colors[0], 24), scaleHue(theme.colors[0], -18)];
}

function prepareTheme(theme) {
    const builder = BUILDERS[theme.grammar];
    if (!builder) throw new Error(`${theme.id}: unknown grammar ${theme.grammar}`);
    const probe = builder('#808080', probeCtx, tonesFor(theme));
    const stack = probe.guardBox ? governingStack(probe.layers, probe.guardBox) : [];
    theme.colors = theme.colors.map((c) => enforceStackContrast(c, stack));
    // The thirteenth plate is derived, never hand-tuned: the ramp's accent step pushed clear of
    // its neighbours, then corrected against this grammar's own stack like every other colour.
    theme.colors.push(enforceStackContrast(scaleHue(theme.colors[4], 42), stack));
    theme.home = theme.colors[0];
    const tone = tonesFor(theme).map((t) => enforceStackContrast(t, stack));
    if (theme.tone) {
        theme.tone = theme.tone.map((t) => enforceStackContrast(t, stack));
        theme._tone = tone;
    } else {
        theme._tone = tone;
    }
    theme._guardStack = stack;
}

/* ===========================================================================
 * ASSET IO
 * ======================================================================== */
const WEIGHT_CHAIN = ['regular', 'thin', 'light', 'bold', 'fill'];

function assetName(theme, glyph, weight) {
    const w = weight ?? theme.weight ?? 'regular';
    return { dir: w, weight: w, file: w === 'regular' ? `${glyph}.svg` : `${glyph}-${w}.svg` };
}

/**
 * Vendored-first, then CDN. The thin weight is only vendored for part of the pack, so the
 * line grammar walks a fallback chain and never depends on the network for what it can
 * already read from disk.
 */
function vendorPathFor(theme, glyph, weight) {
    const a = assetName(theme, glyph, weight);
    return join(vendorDir, a.dir, a.file);
}

function resolveWeight(theme, glyph) {
    const preferred = theme.weight ?? 'regular';
    for (const w of [preferred, ...WEIGHT_CHAIN]) {
        if (existsSync(vendorPathFor(theme, glyph, w))) return w;
    }
    return preferred;
}

async function loadSvg(theme, glyph) {
    const weight = resolveWeight(theme, glyph);
    const a = assetName(theme, glyph, weight);
    const path = join(vendorDir, a.dir, a.file);
    if (existsSync(path)) { theme._usedWeight = weight; return readFileSync(path, 'utf8'); }
    mkdirSync(join(vendorDir, a.dir), { recursive: true });
    const res = await fetch(`${PHOSPHOR_CDN}/${a.weight}/${a.file}`);
    if (!res.ok) throw new Error(`${res.status} for ${a.dir}/${a.file}`);
    const text = await res.text();
    writeFileSync(path, text);
    theme._usedWeight = weight;
    return text;
}

function parsePaths(svg) {
    const paths = [];
    for (const match of svg.matchAll(/<path\b([^>]*?)\/?>/g)) {
        const attrs = match[1];
        const d = /\sd="([^"]+)"/.exec(attrs)?.[1];
        if (!d) continue;
        paths.push({
            d,
            opacity: /\sopacity="([^"]+)"/.exec(attrs)?.[1],
            rule: /fill-rule="([^"]+)"/.exec(attrs)?.[1]
        });
    }
    if (paths.length === 0) throw new Error('no <path> found');
    return paths;
}

function argb(hex, alpha = 1) {
    const a = Math.round(Math.min(1, Math.max(0, alpha)) * 255).toString(16).padStart(2, '0');
    return `#${a}${hex.replace('#', '')}`.toUpperCase();
}

/* ===========================================================================
 * GLYPH RASTER (the pixel-sprite and duotone cell grammars)
 * ======================================================================== */
const rasterCache = new Map();

function rasterizeGlyph(theme, glyph, perUnit = 2) {
    const key = `${resolveWeight(theme, glyph)}:${glyph}:${perUnit}`;
    if (rasterCache.has(key)) return rasterCache.get(key);
    const Resvg = loadResvg();
    const svg = readFileSync(vendorPathFor(theme, glyph, resolveWeight(theme, glyph)), 'utf8')
        .replace('currentColor', '#FFFFFF');
    const size = 256 * perUnit;
    const rendered = new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render();
    const out = { size, width: rendered.width, pixels: rendered.pixels };
    rasterCache.set(key, out);
    return out;
}

function cellCoverage(raster, perUnit, gx, gy, x0, y0, cell) {
    const px0 = Math.round((x0 + gx * cell) * perUnit);
    const py0 = Math.round((y0 + gy * cell) * perUnit);
    const px1 = Math.round((x0 + (gx + 1) * cell) * perUnit);
    const py1 = Math.round((y0 + (gy + 1) * cell) * perUnit);
    let sum = 0, count = 0;
    for (let py = py0; py < Math.max(py1, py0 + 1); py++) {
        for (let px = px0; px < Math.max(px1, px0 + 1); px++) {
            if (px < 0 || py < 0 || px >= raster.width || py >= raster.width) continue;
            sum += raster.pixels[(py * raster.width + px) * 4 + 3];
            count++;
        }
    }
    return count ? sum / count / 255 : 0;
}

/** The coverage grid of a glyph over a rect, normalised so the art fills the rect. */
function glyphGrid(theme, glyph, box, n) {
    const perUnit = 2;
    const raster = rasterizeGlyph(theme, glyph, perUnit);
    const grid = [];
    let max = 0;
    for (let gy = 0; gy < n; gy++) {
        const row = [];
        for (let gx = 0; gx < n; gx++) {
            const v = cellCoverage(raster, perUnit, gx, gy, box.x, box.y, box.w / n);
            row.push(v);
            if (v > max) max = v;
        }
        grid.push(row);
    }
    // Re-scale to the glyph's own dynamic range so a hairline glyph still reads as blocks.
    const norm = Math.max(max, 0.35);
    return grid.map((row) => row.map((v) => v / norm));
}

/* ===========================================================================
 * DRAWABLE EMITTER — draws the layer model of icon-grammars.mjs
 * ======================================================================== */
function gradientBlock(indent, stops, from, to) {
    const items = stops.map((s) =>
        `${indent}        <item android:offset="${s.offset}" android:color="${argb(s.color)}"/>`).join('\n');
    return `${indent}    <aapt:attr name="android:fillColor">\n${indent}        <gradient\n${indent}            android:type="linear"\n` +
        `${indent}            android:startX="${from.x}" android:startY="${from.y}"\n` +
        `${indent}            android:endX="${to.x}" android:endY="${to.y}">\n${items}\n${indent}        </gradient>\n${indent}    </aapt:attr>`;
}

function emitLayer(l, indent) {
    const bits = [`android:pathData="${l.d}"`];
    if (l.stroke) {
        // Open geometry: stroke carries the paint, alpha rides on the stroke.
        bits.push(`android:strokeColor="${argb(l.stroke || l.fill, l.fillAlpha ?? 1)}"`);
        bits.push(`android:strokeWidth="${l.strokeWidth}"`);
        bits.push('android:strokeLineCap="round"');
        if (l.kind !== 'overlay') bits.push('android:fillColor="#00000000"');
    } else if (l.fill) {
        bits.push(`android:fillColor="${argb(l.fill, l.fillAlpha ?? 1)}"`);
    } else {
        bits.push('android:fillColor="#00000000"');
    }
    // Attributes must be whitespace-separated. Joining them bare produced
    // `pathData="…Z"android:fillColor=…`, which .NET's XML reader forgives and aapt2 does not:
    // the whole resource compile dies on it.
    return `${indent}<path ${bits.join(' ')} />`;
}

function renderDrawable(theme, moduleIndex, glyphName, plateColor, svg, built) {
    const { plateGradient, layers, glyph } = built;
    const out = [];
    const indent = '    ';

    const behind = layers.filter((l) => l.behind);
    const face = layers.filter((l) => !l.behind && l.kind !== 'ink');
    const ink = layers.filter((l) => l.kind === 'ink');

    for (const l of behind) out.push(emitLayer(l, indent));

    // The plate face: flat, or a three-stop mesh/field gradient. Line grammars have no plate.
    const plate = face.find((l) => l.kind === 'plate');
    if (plate) {
        if (plateGradient) {
            out.push(
                `${indent}<path android:pathData="${plate.d}">\n` +
                gradientBlock(indent, plateGradient, { x: 12, y: 12 }, { x: 244, y: 244 }) +
                `\n${indent}</path>`
            );
        } else {
            out.push(emitLayer({ ...plate, fill: plateColor }, indent));
        }
    }
    for (const l of face.filter((x) => x !== plate)) out.push(emitLayer(l, indent));

    // The glyph, or the cell geometry a raster grammar already rebuilt as ink layers.
    if (!glyph.baked) {
        const paths = parsePaths(svg);
        const pivotX = glyph.pivotX ?? 128, pivotY = glyph.pivotY ?? 128;
        const body = [];
        if (glyph.letterpress) {
            body.push(
                '        <group',
                `            android:pivotX="${pivotX + 4}" android:pivotY="${pivotY + 4}"`,
                `            android:scaleX="${glyph.inset}" android:scaleY="${glyph.inset}"`,
                '            android:translateX="4" android:translateY="4">',
                paths.map((p) => emitLayer(
                    { d: p.d, kind: 'ink', fill: '#000000', fillAlpha: 0.28, rect: {} }, '            '
                )).join('\n'),
                '        </group>'
            );
        }
        body.push(
            '        <group',
            `            android:pivotX="${pivotX}" android:pivotY="${pivotY}"`,
            `            android:scaleX="${glyph.inset}" android:scaleY="${glyph.inset}">`,
            paths.map((p) => {
                const alpha = p.opacity ? Number(p.opacity) : 1;
                return emitLayer(
                    {
                        d: p.d, kind: 'ink', rect: {},
                        fill: glyph.ink ?? '#FFFFFF',
                        fillAlpha: glyph.ink ? alpha : (alpha < 1 ? alpha : 1)
                    },
                    '            '
                );
            }).join('\n'),
            '        </group>'
        );
        out.push(body.join('\n'));
    } else {
        ink.forEach((l) => out.push(emitLayer(l, indent)));
    }

    const worst = built._worstRatio ?? 0;
    return `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/brand-icons/build.mjs. Do not edit.
     Theme: ${theme.name} (${theme.id}) | Grammar: ${theme.grammar}
     ${GRAMMARS[theme.grammar]}
     Glyph: ${glyphName} (phosphor/${theme._usedWeight}) | Module: ${MODULES[moduleIndex][2]}
     Plate: ${plateColor} | worst guarded white-ink ratio: ${worst.toFixed(2)}:1 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="32dp"
    android:height="32dp"
    android:viewportWidth="256"
    android:viewportHeight="256">
${out.join('\n')}
</vector>
`;
}

/* ===========================================================================
 * GEOMETRY SIGNATURE — the fingerprint the lineup tests compare
 * ======================================================================== */
function geometrySignature(theme, built) {
    const kinds = built.layers.map((l) =>
        `${l.kind}${l.stroke ? `s${Math.round(l.strokeWidth)}` : ''}${l.behind ? 'b' : ''}${l.solid ? 'x' : ''}`
    );
    const plate = built.layers.find((l) => l.kind === 'plate');
    const curveCount = built.layers.filter((l) => l.d.includes('A') || l.d.includes('Q')).length;
    return [
        theme.grammar,
        kinds.join('+'),
        `curves:${curveCount}`,
        `plate:${plate ? (/L/.test(plate.d) && !/A/.test(plate.d) ? 'poly' : 'curve') : 'none'}`,
        `glyph:${built.glyph.baked ? 'rebuilt-cells' : built.glyph.noPlate ? 'bare-ink' : 'vector'}@${built.glyph.inset ?? 'n/a'}` +
            (built.glyph.pivotX ? `→sub(${built.glyph.pivotX},${built.glyph.pivotY})` : '') +
            (built.glyph.letterpress ? '+press' : ''),
        `guard:${built.guardBox ? `${built.guardBox.x},${built.guardBox.y},${built.guardBox.w}x${built.guardBox.h}` : 'none'}`,
        built.plateGradient ? 'field-grad' : 'flat'
    ].join('|');
}

/* ===========================================================================
 * CATALOG + REPORT
 * ======================================================================== */
function renderCatalog(entries) {
    const grammarLines = entries.map((e) =>
        `        "${e.id}" to IconGrammar.${e.grammar},`).join('\n');
    return `package com.securevault.pro.presentation.components

import androidx.compose.ui.graphics.Color
import com.securevault.pro.R
import com.securevault.pro.presentation.theme.IconGrammar

/**
 * GENERATED by tools/brand-icons/build.mjs — do not edit by hand.
 *
 * Fifteen themes, fifteen icon construction grammars. Each theme's board is drawn with its
 * own silhouette, depth treatment and glyph placement — a superellipse with a specular strip,
 * a pixel grid rebuilt from the glyph's own coverage, a bento box whose glyph is parked in a
 * sub-box, a diagonal duotone split — so a theme switch changes how the icons are *built*,
 * not just what they are painted in. The glyph art is the MIT Phosphor pack; the grammar is
 * authored in tools/brand-icons/icon-grammars.mjs and pinned here by [IconGrammar] plus
 * [geometry].
 */
enum class VaultModule(val key: String, val label: String) {
${MODULES.map(([key, slug, label]) => `    ${key}("${slug}", "${label}"),`).join('\n')}
}

object BrandIconCatalog {

    private val icons: Map<String, Map<VaultModule, Int>> = mapOf(
${entries.map((e) => `        "${e.id}" to mapOf(\n${e.mods.map((m, i) => `            VaultModule.${MODULES[i][0]} to R.drawable.${m}`).join(',\n')}\n        ),`).join('\n')}
    )

    /** Baked into the drawable too, exposed here for borders, badges and labels. */
    private val accents: Map<String, Map<VaultModule, ULong>> = mapOf(
${entries.map((e) => `        "${e.id}" to mapOf(\n${e.colors.map((c, i) => `            VaultModule.${MODULES[i][0]} to 0xFF${c.slice(1)}uL`).join(',\n')}\n        ),`).join('\n')}
    )

    /** The construction grammar the board really draws with. Mirrors IconGrammar in the theme layer. */
    private val grammars: Map<String, IconGrammar> = mapOf(
${grammarLines}
    )

    /** Per-module geometry fingerprint: primitives emitted, plate curve, glyph placement, guard box. */
    private val geometries: Map<String, List<String>> = mapOf(
${entries.map((e) => `        "${e.id}" to listOf(\n${e.signatures.map((s) => `            "${s.replace(/"/g, '\\"')}"`).join(',\n')}\n        ),`).join('\n')}
    )

    fun icon(themeId: String, module: VaultModule): Int =
        icons[themeId]?.get(module) ?: icons.getValue(VAULT_DEFAULT).getValue(module)

    fun accent(themeId: String, module: VaultModule): Color =
        argbToColor(accents[themeId]?.get(module) ?: accents.getValue(VAULT_DEFAULT).getValue(module))

    /**
     * A brand accent is stored as 0xFFRRGGBB, and \`Color(ULong)\` is Color's value-class
     * constructor: it takes the already-packed internal representation, so the blue
     * channel lands in the 6-bit color space index and drawing the color throws
     * ArrayIndexOutOfBoundsException on an 18-slot array. Only the Long factory packs
     * ARGB as sRGB, so the conversion has to happen here, once.
     */
    private fun argbToColor(argb: ULong): Color = Color(argb.toLong() and 0xFFFFFFFFL)

    /** The twelve modules the home board shows, in grid order (3 rows of 4). */
    val board: List<VaultModule> = VaultModule.entries.filter { it != VaultModule.HOME }

    /** The brand's own ramp for the twelve board modules, in grid order. */
    fun ramp(themeId: String): List<Color> =
        VaultModule.entries.filter { it != VaultModule.HOME }.map { accent(themeId, it) }

    /** The theme's icon grammar, as the board draws it. Unknown ids fall to the default brand. */
    fun grammar(themeId: String): IconGrammar = grammars[themeId] ?: grammars.getValue(VAULT_DEFAULT)

    /** One geometry fingerprint per module — the tripwire for "same icon, different border". */
    fun geometry(themeId: String): List<String> = geometries[themeId] ?: geometries.getValue(VAULT_DEFAULT)

    private const val VAULT_DEFAULT = "${DEFAULT_THEME}"
}
`;
}

function renderReadme(reportRows) {
    const rows = reportRows.map((r) =>
        `| \`${r.theme}\` | ${r.name} | \`${r.grammar}\` | ${r.description} | ${r.worstRatio.toFixed(2)}:1 |`);
    return `# Brand icon pack — fifteen construction grammars

Generated Android VectorDrawables for the fifteen shipped themes. Glyph art is
[Phosphor Icons](https://phosphoricons.com) v2.1.1 (MIT), vendored under \`vendor/\`; the
*grammar* — silhouette, depth layers, glyph placement, and for the two raster families the
cell rebuild itself — is authored in \`icon-grammars.mjs\`. Run \`node build.mjs\` to
regenerate; run \`node --test\` to re-prove the guard contract without writing assets.

Every theme draws its own family. The lineup guard test compares the per-module geometry
fingerprint emitted into \`BrandIconCatalog.geometry()\`, so two themes that differ only in
colour fail the build.

| Theme id | Name | Grammar | Construction | Worst guarded white-ink ratio |
|---|---|---|---|---|
${rows.join('\n')}

Contrast: the guard does not check the bare plate. Each grammar declares the overlays that can
land under the glyph, the guard partitions the glyph's guard box by those overlay rects and
composites every stack that really covers a cell, and the ramp is darkened by the smallest
scale that clears WCAG AA (≥${AA_PLATE_TEXT}:1, generated to ≥${PLATE_GUARD}:1 for 8-bit
rounding) at the worst cell. A layer marked \`decor\` that overlaps the guard box, or a
\`frame\` ring that does not sit clear of it, fails the build — so a mislabelled layer cannot
dodge the check.

Attribution:
- Phosphor Icons — MIT, Copyright (c) 2018 Windo Avalar (\`LICENSE.phosphor.txt\`).
`;
}

/* ===========================================================================
 * MAIN
 * ======================================================================== */
async function main() {
    for (const theme of THEMES) prepareTheme(theme);

    mkdirSync(vendorDir, { recursive: true });
    mkdirSync(drawableDir, { recursive: true });
    const entries = [];
    const reportRows = [];
    let count = 0;
    for (const theme of THEMES) {
        const mods = [];
        const signatures = [];
        const colors = [];
        let worst = Infinity;
        for (let i = 0; i < MODULES.length; i++) {
            const glyphName = MODULES[i][3];
            await loadSvg(theme, glyphName);
            const svg = readFileSync(vendorPathFor(theme, glyphName, theme._usedWeight), 'utf8');
            const plate = theme.colors[i] ?? theme.home;
            const ctx = { gridFor: (box, n) => glyphGrid(theme, glyphName, box, n) };
            const built = BUILDERS[theme.grammar](plate, ctx, theme._tone);
            const verdict = verifyGrammar(built, plate, PLATE_GUARD);
            built._worstRatio = verdict.worst;
            worst = Math.min(worst, verdict.worst);
            const resName = `ic_b_${theme.id}_${MODULES[i][1]}`;
            writeFileSync(
                join(drawableDir, `${resName}.xml`),
                renderDrawable(theme, i, glyphName, plate, svg, built)
            );
            mods.push(resName);
            colors.push(plate);
            signatures.push(geometrySignature(theme, built));
            count++;
        }
        if (new Set(signatures).size !== 1) {
            throw new Error(`${theme.id}: one theme must draw ONE coherent family (got ${new Set(signatures).size} signatures)`);
        }
        reportRows.push({
            theme: theme.id,
            name: theme.name,
            grammar: theme.grammar,
            description: GRAMMARS[theme.grammar],
            glyphPlacement: theme.grammar === 'BENTO_SUBBOX' ? 'parked sub-box' : 'centred',
            guardStack: theme._guardStack.length,
            worstRatio: worst,
            homeRatio: contrastRatio(INK_WHITE, theme.home).toFixed(2)
        });
        entries.push({
            id: theme.id, name: theme.name, grammar: theme.grammar, mods,
            colors, signature: signatures[0], signatures
        });
    }

    const uniqueSignatures = new Set(entries.map((e) => e.signature));
    if (uniqueSignatures.size !== entries.length) {
        const dupes = entries.filter((e, i) => entries.findIndex((o) => o.signature === e.signature) !== i);
        throw new Error(`two themes share an icon geometry fingerprint: ${dupes.map((d) => d.id).join(', ')}`);
    }

    let removed = 0;
    for (const id of RETIRED_THEMES) {
        for (const [, slug] of MODULES) {
            const path = join(drawableDir, `ic_b_${id}_${slug}.xml`);
            if (existsSync(path)) { rmSync(path); removed++; }
        }
    }

    writeFileSync(catalogOut, renderCatalog(entries));
    writeFileSync(join(here, 'README.md'), renderReadme(reportRows));
    writeFileSync(reportOut, JSON.stringify({
        generatedBy: 'tools/brand-icons/build.mjs',
        plateGuard: PLATE_GUARD, aaText: AA_PLATE_TEXT, themes: reportRows, entries
    }, null, 2));

    try {
        const phosphor = await fetch('https://cdn.jsdelivr.net/npm/@phosphor-icons/core@2/LICENSE');
        if (phosphor.ok) writeFileSync(join(here, 'LICENSE.phosphor.txt'), await phosphor.text());
    } catch {
        console.warn('license fetch skipped (offline?): keeping the vendored LICENSE.phosphor.txt');
    }

    console.log(`wrote ${count} drawables across ${THEMES.length} themes (${uniqueSignatures.size} distinct grammars), removed ${removed} retired drawables`);
    for (const r of reportRows) {
        console.log(`  ${r.theme.padEnd(7)} ${r.grammar.padEnd(18)} worst ${r.worstRatio.toFixed(2)}:1`);
    }
}

main().catch((err) => { console.error(err.stack || err.message); process.exit(1); });
