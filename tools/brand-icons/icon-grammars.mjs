#!/usr/bin/env node
/**
 * Icon construction grammars — the pure half of the brand board builder.
 *
 * This module owns the fifteen grammars. `build.mjs` imports it, rasterises the source
 * glyphs, and writes the drawables; the node tests import it directly.
 *
 * Why it exists: the board used to ship as one geometry in fifteen colours. `renderDrawable`
 * drew the same squircle for every theme, so a theme switch moved the paint and nothing else —
 * the exact failure the owner rejected. Each theme now owns a different *construction*: a
 * different silhouette, a different depth treatment, a different glyph placement, and for two
 * families the glyph is not drawn at all but rebuilt as cells from its own alpha coverage.
 *
 * Glyph art is still the MIT Phosphor pack (vendored, never hand-drawn). The grammar — how that
 * art is seated, framed, split and lit — is authored here.
 *
 * LAYER KINDS, and the contract each one is checked against before anything is written:
 *   plate   the face the glyph sits on. Must hold white ink at AA through its worst overlay.
 *   overlay modulates the plate where it covers it (white bloom, black hatch, solid split).
 *           The guard partitions the glyph's guard box by these rects, composites every stack
 *           that really covers a cell, and measures white ink at the worst cell.
 *   frame   a ring stroke (rim, inset rule). Validated as an annulus: the guard box must lie
 *           wholly inside the hole, so a ring can never be smuggled under the glyph.
 *   decor   geometry outside the glyph (ticks, glow rings behind the plate). Validated by
 *           non-intersection with the guard box.
 *   ink     the glyph itself, or cells rebuilt from it.
 *
 * A mislabelled layer fails the build. That is the point of the model: "same icon, different
 * border" cannot hide inside it, and neither can a highlight that quietly washes out the glyph.
 */

/* ===========================================================================
 * COLOUR MATHS (WCAG 2.1, identical to Role.kt's VaultContrast)
 * ======================================================================== */
export const INK_WHITE = '#FFFFFF';
export const AA_PLATE_TEXT = 4.5;
/** A hair above AA so the 8-bit baked value still clears the bar the JVM re-measures. */
export const PLATE_GUARD = AA_PLATE_TEXT + 0.1;

export function channelLinear(byte) {
    const s = byte / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}
export function luminance(hex) {
    const [r, g, b] = toRgb(hex);
    return 0.2126 * channelLinear(r) + 0.7152 * channelLinear(g) + 0.0722 * channelLinear(b);
}
export function contrastRatio(fg, bg) {
    const a = luminance(fg), b = luminance(bg);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}
export function toRgb(hex) {
    const n = hex.replace('#', '');
    return [parseInt(n.slice(0, 2), 16), parseInt(n.slice(2, 4), 16), parseInt(n.slice(4, 6), 16)];
}
export function fromRgb(rgb) {
    return '#' + rgb.map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0'))
        .join('').toUpperCase();
}
export function scaleHex(hex, f) {
    return fromRgb(toRgb(hex).map((v) => v * f));
}
/** Source-over of an opaque tint at `alpha` onto an opaque base. */
export function compositeOver(base, tint, alpha) {
    const b = toRgb(base), t = toRgb(tint);
    return fromRgb(b.map((v, i) => v * (1 - alpha) + t[i] * alpha));
}
export function scaleHue(hex, degrees) {
    const [r, g, b] = toRgb(hex).map((v) => v / 255);
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    let h = max === min ? 0 : max === r ? ((g - b) / (max - min) + (g < b ? 6 : 0))
        : max === g ? ((b - r) / (max - min) + 2) : ((r - g) / (max - min) + 4);
    h = (((h * 60 + degrees) % 360) + 360) % 360;
    const l = (max + min) / 2, d = max - min;
    const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
    const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    const m = l - c / 2;
    const seg = [[c, x, 0], [x, c, 0], [0, c, x], [0, x, c], [x, 0, c], [c, 0, x]][Math.floor(h / 60) % 6];
    return fromRgb(seg.map((v) => (v + m) * 255));
}

/**
 * Darken `hex` by the smallest scale that keeps white ink at AA *under the overlay stack that
 * really lands on it*. Hue and channel ratios survive; only luminance moves.
 */
export function enforceStackContrast(hex, stack = [], minimum = PLATE_GUARD) {
    const worst = (candidate) => {
        let value = candidate;
        for (const layer of stack) value = compositeOver(value, layer.tint, layer.alpha);
        return contrastRatio(INK_WHITE, value);
    };
    if (worst(hex) >= minimum) return hex.toUpperCase();
    let f = 1;
    while (f > 0.02 && worst(scaleHex(hex, f)) < minimum) f -= 0.01;
    const fixed = scaleHex(hex, f);
    if (worst(fixed) < minimum) {
        throw new Error(`${hex}: no darkening clears ${minimum}:1 under overlays ${JSON.stringify(stack)}`);
    }
    return fixed;
}

/* ===========================================================================
 * GEOMETRY PRIMITIVES — everything in the 256-unit viewport
 * ======================================================================== */
const f2 = (v) => Math.round(v * 100) / 100;

export function roundRectPath(x, y, w, h, r) {
    const [tl, tr, br, bl] = Array.isArray(r) ? r : [r, r, r, r];
    return `M${f2(x + tl)},${f2(y)}` +
        `H${f2(x + w - tr)}A${f2(tr)},${f2(tr)} 0 0 1 ${f2(x + w)},${f2(y + tr)}` +
        `V${f2(y + h - br)}A${f2(br)},${f2(br)} 0 0 1 ${f2(x + w - br)},${f2(y + h)}` +
        `H${f2(x + bl)}A${f2(bl)},${f2(bl)} 0 0 1 ${f2(x)},${f2(y + h - bl)}` +
        `V${f2(y + tl)}A${f2(tl)},${f2(tl)} 0 0 1 ${f2(x + tl)},${f2(y)}Z`;
}

/** Superellipse |u/a|^n + |v/b|^n = 1, sampled. Sampling beats bezier handles when n moves. */
export function superellipsePath(x, y, w, h, n, steps = 48) {
    const cx = x + w / 2, cy = y + h / 2, a = w / 2, b = h / 2;
    const pts = [];
    for (let i = 0; i < steps; i++) {
        const t = (i / steps) * Math.PI * 2;
        const ct = Math.cos(t), st = Math.sin(t);
        pts.push([
            cx + a * Math.sign(ct) * Math.abs(ct) ** (2 / n),
            cy + b * Math.sign(st) * Math.abs(st) ** (2 / n)
        ]);
    }
    return pathFromPoints(pts);
}

/** A blob: the superellipse with an exponent that varies around the ring — asymmetric by design. */
export function blobPath(x, y, w, h, steps = 64) {
    const cx = x + w / 2, cy = y + h / 2, a = w / 2, b = h / 2;
    const pts = [];
    for (let i = 0; i < steps; i++) {
        const t = (i / steps) * Math.PI * 2;
        const ct = Math.cos(t), st = Math.sin(t);
        const n = 2.2 + 1.6 * Math.abs(Math.sin(t * 2));
        pts.push([
            cx + a * Math.sign(ct) * Math.abs(ct) ** (2 / n),
            cy + b * Math.sign(st) * Math.abs(st) ** (2 / n)
        ]);
    }
    return pathFromPoints(pts);
}

export function pathFromPoints(pts) {
    return `M${f2(pts[0][0])},${f2(pts[0][1])}` +
        pts.slice(1).map((p) => `L${f2(p[0])},${f2(p[1])}`).join('') + 'Z';
}

export const polyPath = pathFromPoints;

export function rectPath(x, y, w, h) {
    return `M${f2(x)},${f2(y)}H${f2(x + w)}V${f2(y + h)}H${f2(x)}Z`;
}

/** Two opposite corners cut on the bias, the other two kept hard. */
export function chamferCutPath(x, y, w, h, c) {
    return `M${f2(x)},${f2(y)}H${f2(x + w - c)}L${f2(x + w)},${f2(y + c)}` +
        `V${f2(y + h)}H${f2(x + c)}L${f2(x)},${f2(y + h - c)}Z`;
}

export function ellipsePath(cx, cy, rx, ry) {
    return `M${f2(cx - rx)},${f2(cy)}` +
        `A${f2(rx)},${f2(ry)} 0 1 0 ${f2(cx + rx)},${f2(cy)}` +
        `A${f2(rx)},${f2(ry)} 0 1 0 ${f2(cx - rx)},${f2(cy)}Z`;
}

/** Lens: two symmetric arcs meeting at hard left/right points. */
export function lensPath(x, y, w, h) {
    const bulge = h * 0.62;
    return `M${f2(x)},${f2(y + h / 2)}Q${f2(x + w / 2)},${f2(y + h / 2 - bulge)} ${f2(x + w)},${f2(y + h / 2)}` +
        `Q${f2(x + w / 2)},${f2(y + h / 2 + bulge)} ${f2(x)},${f2(y + h / 2)}Z`;
}

export function notchedSquarePath(x, y, w, h, c) {
    return `M${f2(x)},${f2(y)}H${f2(x + w - c)}L${f2(x + w)},${f2(y + c)}V${f2(y + h)}H${f2(x)}Z`;
}

/** Hard square with all four corners removed a whole pixel — the sprite frame. */
export function pixelFramePath(x, y, w, h, c) {
    return `M${f2(x + c)},${f2(y)}H${f2(x + w - c)}V${f2(y + c)}H${f2(x + w)}V${f2(y + h - c)}` +
        `H${f2(x + w - c)}V${f2(y + h)}H${f2(x + c)}V${f2(y + h - c)}H${f2(x)}V${f2(y + c)}H${f2(x + c)}Z`;
}

/** Folded sheet: a five-vertex polygon with a raked left shoulder. */
export function foldSheetPath(x, y, w, h) {
    return polyPath([
        [x + w * 0.04, y + h * 0.26], [x + w * 0.5, y], [x + w * 0.96, y + h * 0.24],
        [x + w * 0.86, y + h * 0.98], [x + w * 0.12, y + h * 0.94]
    ]);
}

/** A sine wave across the plate, sampled into a polyline the grammar strokes. */
export function wavePath(x, w, yMid, amp, phase, steps = 32) {
    const pts = [];
    for (let i = 0; i <= steps; i++) {
        const t = i / steps;
        pts.push([x + w * t, yMid + Math.sin(t * Math.PI * 2 + phase) * amp]);
    }
    return `M${pts.map(([px, py]) => `${f2(px)},${f2(py)}`).join('L')}`;
}

/** Cells merged into horizontal runs, so a 16x16 grid costs ~20 paths instead of 256. */
export function pixelRunPaths(mask, n, x0, y0, cell) {
    const taken = mask.map((row) => row.slice());
    const paths = [];
    for (let gy = 0; gy < n; gy++) {
        let gx = 0;
        while (gx < n) {
            if (!taken[gy][gx]) { gx++; continue; }
            let run = 1;
            while (gx + run < n && taken[gy][gx + run]) run++;
            paths.push(rectPath(x0 + gx * cell, y0 + gy * cell, f2(run * cell), cell));
            for (let i = 0; i < run; i++) taken[gy][gx + i] = false;
            gx += run;
        }
    }
    return paths;
}

/* ===========================================================================
 * LAYER MODEL
 * ======================================================================== */
/** @returns a layer: { d, kind, fill, fillAlpha, stroke, strokeWidth, sw, rect, tint, alpha } */
export function layer(kind, spec) {
    if (!['plate', 'overlay', 'frame', 'decor', 'ink'].includes(kind)) {
        throw new Error(`unknown layer kind ${kind}`);
    }
    return {
        kind,
        d: spec.d,
        fill: spec.fill ?? null,
        fillAlpha: spec.fillAlpha ?? 1,
        stroke: spec.stroke ?? null,
        strokeWidth: spec.strokeWidth ?? 0,
        solid: spec.solid ?? false,
        behind: spec.behind ?? false,
        rect: spec.rect ?? null
    };
}

const intersects = (a, b) => !!(a && b) &&
    a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/** A frame ring is safe only if the guard box sits wholly inside the hole it encloses. */
function frameClearsGuard(l, guard) {
    if (!l.rect || !guard) return false;
    const band = Math.max(l.strokeWidth, 1) / 2 + 1;
    return l.rect.x + band < guard.x && l.rect.y + band < guard.y &&
        l.rect.x + l.rect.w - band > guard.x + guard.w &&
        l.rect.y + l.rect.h - band > guard.y + guard.h;
}

/** The overlay stack covering one point of the plate. */
export function stackAt(layers, point) {
    return layers
        .filter((l) => l.kind === 'overlay' && !l.solid && l.rect &&
            point.x >= l.rect.x && point.x <= l.rect.x + l.rect.w &&
            point.y >= l.rect.y && point.y <= l.rect.y + l.rect.h)
        .map((l) => ({ tint: (l.fill || l.stroke).toUpperCase(), alpha: l.fillAlpha ?? 1 }));
}

/** Partition the guard box by every overlay edge; return the worst white-ink cell. */
export function worstCell(layers, guard, plateHex) {
    const xs = new Set([guard.x, guard.x + guard.w]);
    const ys = new Set([guard.y, guard.y + guard.h]);
    for (const l of layers) {
        if (l.kind !== 'overlay' || !l.rect) continue;
        xs.add(clamp(l.rect.x, guard));
        xs.add(clamp(l.rect.x + l.rect.w, guard));
        ys.add(clampY(l.rect.y, guard));
        ys.add(clampY(l.rect.y + l.rect.h, guard));
    }
    let worst = Infinity, worstStack = null;
    for (const x of xs) {
        for (const y of ys) {
            const stack = stackAt(layers, { x, y });
            let value = plateHex;
            for (const s of stack) value = compositeOver(value, s.tint, s.alpha);
            const ratio = contrastRatio(INK_WHITE, value);
            if (ratio < worst) { worst = ratio; worstStack = stack; }
        }
    }
    return { ratio: worst, stack: worstStack ?? [] };
}
const clamp = (v, g) => Math.max(g.x, Math.min(g.x + g.w, v));
const clampY = (v, g) => Math.max(g.y, Math.min(g.y + g.h, v));

/** The overlay stack a plate must be corrected against, before any colour exists. */
export function governingStack(layers, guard) {
    return worstCell(layers.length ? layers : [], guard, '#B0B0B0').stack;
}

/**
 * Full build-time verification of one built grammar for one plate colour. Returns the numbers
 * the report quotes; throws on any violation, which is how a bad grammar kills the build.
 */
export function verifyGrammar(built, plateHex, minimum = PLATE_GUARD) {
    const guard = built.guardBox;
    if (!guard) throw new Error('grammar declares no guard box');
    const measured = [];

    // 1. Layer bookkeeping: nothing may be smuggled under the glyph.
    for (const l of built.layers) {
        if (!l.rect) throw new Error(`layer without a declared rect: ${l.d.slice(0, 24)}…`);
        if (l.kind === 'decor' && !l.behind && intersects(l.rect, guard)) {
            throw new Error(`decor layer overlaps the guard box: ${JSON.stringify(l.rect)}`);
        }
        if (l.kind === 'frame' && !frameClearsGuard(l, guard)) {
            throw new Error(`frame ring is not clear of the guard box: ${JSON.stringify(l.rect)}`);
        }
    }

    // 2. The worst guarded cell of the plate under its real overlay stack.
    const { ratio, stack } = worstCell(built.layers, guard, plateHex);
    measured.push({ label: 'plate', value: Number(ratio.toFixed(3)), stack });
    if (ratio < minimum) {
        throw new Error(`worst guarded cell is ${ratio.toFixed(2)}:1 under ${JSON.stringify(stack)} on ${plateHex}`);
    }

    // 3. Solid split fills replace the plate: measure them in their own right.
    for (const l of built.layers.filter((x) => x.kind === 'overlay' && x.solid)) {
        const r = contrastRatio(INK_WHITE, l.fill);
        measured.push({ label: 'solid-split', value: Number(r.toFixed(3)) });
        if (r < minimum) {
            throw new Error(`solid split ${l.fill} is ${r.toFixed(2)}:1 (< ${minimum}:1)`);
        }
    }

    // 4. Gradient fields: every stop, including the interpolated midpoint the axis produces.
    if (built.plateGradient) {
        for (const stop of built.plateGradient) {
            const r = contrastRatio(INK_WHITE, stop.color);
            measured.push({ label: 'gradient-stop', value: Number(r.toFixed(3)) });
            if (r < AA_PLATE_TEXT) {
                throw new Error(`gradient stop ${stop.color} is ${r.toFixed(2)}:1 (< ${AA_PLATE_TEXT}:1)`);
            }
        }
    }

    return { worst: Math.min(...measured.map((m) => m.value)), stack, measured };
}

/* ===========================================================================
 * THE FIFTEEN GRAMMARS
 * ======================================================================== */
export const GRAMMARS = {
    GLASS_SILVERWARE: 'frosted pane: superellipse plate, two-stop specular strip, inset rim ring',
    COMFORT_SHOULDER: 'asymmetric shoulder square, wide gutter, base band, no gloss, no rim',
    NEUMORPH_EXTRUDE: 'stadium plate extruded by light+dark crescents behind it, recessed inner seat',
    NEON_CHAMFER: 'opposite-corner chamfer, hollow glow ring behind, bright edge ticks',
    CLAY_PUFF: 'puffed squircle, crown bloom, foot shade crescent, thick soft rim',
    PIXEL_SPRITE: 'notched hard square, glyph rebuilt on a 16-cell grid, two-tone limited palette',
    CHROME_LENS: 'lens silhouette, five crossed chrome bands, diagonal specular blade',
    MONOLINE_RING: 'no plate: single-weight hairline ring plus inner rule, thin-weight outline glyph in ink',
    MESH_BLOB: 'asymmetric blob filled by a three-stop mesh, two translucent hue lobes',
    ORIGAMI_FOLD: 'folded-sheet pentagon in three hard facets with two crease strokes',
    BENTO_SUBBOX: 'rounded box partitioned internally, glyph parked in the largest sub-box',
    ENGRAVED_PLATE: 'sharp plate, double hairline frame, diagonal hatch, letterpress glyph',
    DUOTONE_SPLIT: 'plate split on the diagonal, glyph rebuilt as two-tone blocks plus overlap band',
    WAVE_FIELD: 'vertical gradient field plate with three crossed wave bands',
    OLED_NOTCH: 'sharp plate with one corner notched, bright rule under the foot, tight inset'
};

const B = 256;

export const BUILDERS = {
    /* 1 — iOS Glass: frosted translucent pane with a layered specular highlight. */
    GLASS_SILVERWARE(c) {
        const plate = superellipsePath(10, 10, 236, 236, 4);
        return {
            grammar: 'GLASS_SILVERWARE',
            guardBox: { x: 84, y: 84, w: 88, h: 84 },
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 10, y: 10, w: 236, h: 236 } }),
                layer('overlay', { d: superellipsePath(20, 18, 216, 104, 4), fill: '#FFFFFF', fillAlpha: 0.22, rect: { x: 20, y: 18, w: 216, h: 104 } }),
                layer('overlay', { d: superellipsePath(34, 24, 188, 48, 4), fill: '#FFFFFF', fillAlpha: 0.28, rect: { x: 34, y: 24, w: 188, h: 48 } }),
                layer('overlay', { d: superellipsePath(18, 190, 220, 58, 4), fill: '#000000', fillAlpha: 0.12, rect: { x: 18, y: 190, w: 220, h: 58 } }),
                layer('frame', { d: superellipsePath(22, 22, 212, 212, 4), stroke: '#FFFFFF', strokeWidth: 4, rect: { x: 22, y: 22, w: 212, h: 212 } })
            ],
            glyph: { inset: 0.54 }
        };
    },

    /* 2 — Samsung One UI: soft rounded squares, generous gutters, nothing glossy. */
    COMFORT_SHOULDER(c) {
        const plate = roundRectPath(16, 16, 224, 224, [64, 64, 92, 92]);
        return {
            grammar: 'COMFORT_SHOULDER',
            guardBox: { x: 92, y: 92, w: 72, h: 72 },
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 16, y: 16, w: 224, h: 224 } }),
                layer('overlay', { d: roundRectPath(16, 178, 224, 62, [0, 0, 92, 92]), fill: '#000000', fillAlpha: 0.15, rect: { x: 16, y: 178, w: 224, h: 62 } }),
                layer('overlay', { d: roundRectPath(16, 16, 224, 26, [64, 64, 0, 0]), fill: '#FFFFFF', fillAlpha: 0.12, rect: { x: 16, y: 16, w: 224, h: 26 } }),
                layer('frame', { d: roundRectPath(30, 30, 196, 196, [52, 52, 78, 78]), stroke: '#FFFFFF', strokeWidth: 3, rect: { x: 30, y: 30, w: 196, h: 196 } })
            ],
            glyph: { inset: 0.48 }
        };
    },

    /* 3 — Neumorphic Soft: the surface is extruded, and the glyph sits in a pressed seat. */
    NEUMORPH_EXTRUDE(c) {
        const plate = roundRectPath(26, 26, 204, 204, 102);
        const seat = roundRectPath(62, 62, 132, 132, 66);
        return {
            grammar: 'NEUMORPH_EXTRUDE',
            guardBox: { x: 86, y: 86, w: 84, h: 84 },
            platePath: plate,
            layers: [
                layer('decor', { d: roundRectPath(34, 34, 204, 204, 102), fill: '#000000', fillAlpha: 0.40, behind: true, rect: { x: 34, y: 34, w: 204, h: 204 } }),
                layer('decor', { d: roundRectPath(18, 18, 204, 204, 102), fill: '#FFFFFF', fillAlpha: 0.90, behind: true, rect: { x: 18, y: 18, w: 204, h: 204 } }),
                layer('plate', { d: plate, fill: c, rect: { x: 26, y: 26, w: 204, h: 204 } }),
                layer('overlay', { d: seat, fill: '#000000', fillAlpha: 0.13, rect: { x: 62, y: 62, w: 132, h: 132 } }),
                layer('overlay', { d: roundRectPath(62, 62, 132, 40, 40), fill: '#000000', fillAlpha: 0.10, rect: { x: 62, y: 62, w: 132, h: 40 } }),
                layer('frame', { d: roundRectPath(54, 54, 148, 148, 74), stroke: '#FFFFFF', strokeWidth: 5, rect: { x: 54, y: 54, w: 148, h: 148 } })
            ],
            glyph: { inset: 0.46 }
        };
    },

    /* 4 — Cyberpunk Neon: bevelled cuts, a glow ring behind the plate, ticks on the corners. */
    NEON_CHAMFER(c) {
        const plate = chamferCutPath(28, 28, 200, 200, 60);
        return {
            grammar: 'NEON_CHAMFER',
            guardBox: { x: 86, y: 86, w: 84, h: 84 },
            platePath: plate,
            layers: [
                layer('decor', { d: chamferCutPath(6, 6, 244, 244, 68), stroke: c, strokeWidth: 16, behind: true, rect: { x: 6, y: 6, w: 244, h: 244 } }),
                layer('plate', { d: plate, fill: c, rect: { x: 28, y: 28, w: 200, h: 200 } }),
                layer('overlay', { d: rectPath(28, 28, 200, 6), fill: '#FFFFFF', fillAlpha: 0.22, rect: { x: 28, y: 28, w: 200, h: 6 } }),
                layer('overlay', { d: rectPath(28, 216, 200, 12), fill: '#000000', fillAlpha: 0.18, rect: { x: 28, y: 216, w: 200, h: 12 } }),
                layer('frame', { d: chamferCutPath(38, 38, 180, 180, 54), stroke: '#FFFFFF', strokeWidth: 3, rect: { x: 38, y: 38, w: 180, h: 180 } }),
                layer('decor', { d: `${rectPath(40, 214, 34, 5)}${rectPath(182, 37, 34, 5)}`, fill: '#FFFFFF', fillAlpha: 0.9, rect: { x: 182, y: 37, w: 34, h: 182 } })
            ],
            glyph: { inset: 0.52 }
        };
    },

    /* 5 — Claymorphism: puffy form, bloom on the crown, shade under the foot, soft rim. */
    CLAY_PUFF(c) {
        const plate = superellipsePath(14, 14, 228, 228, 3.2);
        return {
            grammar: 'CLAY_PUFF',
            guardBox: { x: 88, y: 92, w: 80, h: 72 },
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 14, y: 14, w: 228, h: 228 } }),
                layer('overlay', { d: ellipsePath(128, 202, 96, 44), fill: '#000000', fillAlpha: 0.14, rect: { x: 32, y: 158, w: 192, h: 88 } }),
                layer('overlay', { d: ellipsePath(128, 92, 88, 54), fill: '#FFFFFF', fillAlpha: 0.20, rect: { x: 40, y: 38, w: 176, h: 108 } }),
                layer('frame', { d: superellipsePath(26, 26, 204, 204, 3.2), stroke: '#FFFFFF', strokeWidth: 7, rect: { x: 26, y: 26, w: 204, h: 204 } })
            ],
            glyph: { inset: 0.48 }
        };
    },

    /* 6 — Retro 8-Bit: the glyph is rebuilt on a 16-cell grid. Staircase edges, two tones,
     * no curves in the art at all. */
    PIXEL_SPRITE(c, ctx) {
        const n = 16;
        const box = { x: 44, y: 44, w: 168, h: 168 };
        const cell = box.w / n;
        const grid = ctx.gridFor(box, n);
        const solid = grid.map((row) => row.map((v) => v >= 0.52));
        const mid = grid.map((row) => row.map((v) => v >= 0.16 && v < 0.52));
        const plate = pixelFramePath(16, 16, 224, 224, 18);
        const ink = pixelRunPaths(solid, n, box.x, box.y, cell)
            .map((d) => layer('ink', { d, fill: '#FFFFFF', rect: { x: box.x, y: box.y, w: box.w, h: box.w } }));
        const inkMid = pixelRunPaths(mid, n, box.x, box.y, cell)
            .map((d) => layer('ink', { d, fill: '#FFFFFF', fillAlpha: 0.52, rect: { x: box.x, y: box.y, w: box.w, h: box.w } }));
        return {
            grammar: 'PIXEL_SPRITE',
            guardBox: box,
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 16, y: 16, w: 224, h: 224 } }),
                layer('overlay', { d: rectPath(16, 226, 224, 14), fill: '#000000', fillAlpha: 0.16, rect: { x: 16, y: 226, w: 224, h: 14 } }),
                ...ink,
                ...inkMid
            ],
            glyph: { baked: true },
            grid: { n, box }
        };
    },

    /* 7 — Liquid Metal: a lens cut by five chrome bands and swept by a specular blade. */
    CHROME_LENS(c) {
        const plate = lensPath(8, 44, 240, 168);
        const layers = [layer('plate', { d: plate, fill: c, rect: { x: 8, y: 44, w: 240, h: 168 } })];
        const bandH = 168 / 5;
        for (let i = 0; i < 5; i++) {
            const y = 44 + i * bandH;
            layers.push(layer('overlay', {
                d: rectPath(8, y, 240, bandH),
                fill: i % 2 === 0 ? '#FFFFFF' : '#000000',
                fillAlpha: i % 2 === 0 ? 0.20 : 0.26,
                rect: { x: 8, y: f2(y), w: 240, h: f2(bandH) }
            }));
        }
        layers.push(layer('overlay', {
            d: polyPath([[36, 212], [82, 212], [206, 44], [160, 44]]),
            fill: '#FFFFFF', fillAlpha: 0.22, rect: { x: 36, y: 44, w: 170, h: 168 }
        }));
        layers.push(layer('frame', {
            d: lensPath(20, 52, 216, 152), stroke: '#FFFFFF', strokeWidth: 3, rect: { x: 20, y: 52, w: 216, h: 152 }
        }));
        return {
            grammar: 'CHROME_LENS',
            guardBox: { x: 96, y: 100, w: 64, h: 58 },
            platePath: plate,
            layers,
            glyph: { inset: 0.40 }
        };
    },

    /* 8 — Minimal Line: no plate at all. One weight rings the art and the art is a thin
     * outline glyph in the theme's own ink, which has to clear AA on the card. */
    MONOLINE_RING(c) {
        const ring = roundRectPath(18, 18, 220, 220, 54);
        return {
            grammar: 'MONOLINE_RING',
            lineMode: true,
            guardBox: { x: 78, y: 78, w: 100, h: 100 },
            platePath: ring,
            layers: [
                layer('frame', { d: ring, stroke: c, strokeWidth: 7, rect: { x: 18, y: 18, w: 220, h: 220 } }),
                layer('frame', { d: roundRectPath(34, 34, 188, 188, 44), stroke: c, strokeWidth: 2.5, rect: { x: 34, y: 34, w: 188, h: 188 } })
            ],
            glyph: { inset: 0.58, ink: c, noPlate: true }
        };
    },

    /* 9 — Gradient Mesh: the fill is the grammar. A three-stop mesh across an asymmetric
     * blob, with two translucent hue lobes drifting over it. */
    MESH_BLOB(c, ctx, tone) {
        const plate = blobPath(12, 12, 232, 232);
        return {
            grammar: 'MESH_BLOB',
            guardBox: { x: 88, y: 88, w: 80, h: 80 },
            platePath: plate,
            plateGradient: [
                { offset: 0, color: c },
                { offset: 0.5, color: tone[0] },
                { offset: 1, color: tone[1] }
            ],
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 12, y: 12, w: 232, h: 232 } }),
                layer('overlay', { d: ellipsePath(96, 104, 74, 62), fill: tone[0], fillAlpha: 0.45, rect: { x: 22, y: 42, w: 148, h: 124 } }),
                layer('overlay', { d: ellipsePath(176, 182, 62, 54), fill: tone[1], fillAlpha: 0.40, rect: { x: 114, y: 128, w: 124, h: 108 } })
            ],
            glyph: { inset: 0.50 }
        };
    },

    /* 10 — Origami: folded planes. Three hard facets and two creases; no soft shading. */
    ORIGAMI_FOLD(c) {
        const plate = foldSheetPath(12, 10, 232, 236);
        return {
            grammar: 'ORIGAMI_FOLD',
            guardBox: { x: 88, y: 98, w: 80, h: 72 },
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 12, y: 10, w: 232, h: 236 } }),
                layer('overlay', { d: polyPath([[21, 71], [128, 10], [128, 246], [39, 241]]), fill: '#000000', fillAlpha: 0.20, rect: { x: 21, y: 10, w: 107, h: 236 } }),
                layer('overlay', { d: polyPath([[128, 10], [235, 67], [213, 244], [128, 246]]), fill: '#FFFFFF', fillAlpha: 0.16, rect: { x: 128, y: 10, w: 107, h: 236 } }),
                layer('overlay', { d: polyPath([[128, 10], [186, 96], [128, 96]]), fill: '#FFFFFF', fillAlpha: 0.22, rect: { x: 128, y: 10, w: 58, h: 86 } }),
                layer('overlay', { d: 'M128,10L128,246', stroke: '#000000', strokeWidth: 4, fill: '#000000', fillAlpha: 0.18, rect: { x: 126, y: 10, w: 4, h: 236 } }),
                layer('decor', { d: 'M21,71L235,67', stroke: '#FFFFFF', strokeWidth: 3, rect: { x: 21, y: 67, w: 214, h: 31 } })
            ],
            glyph: { inset: 0.44 }
        };
    },

    /* 11 — Bento Grid: the box is partitioned and the glyph is parked in the biggest cell.
     * The off-centre placement is the signature; no other grammar does it. */
    BENTO_SUBBOX(c) {
        const plate = roundRectPath(16, 16, 224, 224, 46);
        const cutX = 158, cutY = 106;
        return {
            grammar: 'BENTO_SUBBOX',
            guardBox: { x: 36, y: 120, w: 108, h: 104 },
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 16, y: 16, w: 224, h: 224 } }),
                layer('overlay', { d: rectPath(cutX, 22, 5, 212), fill: '#000000', fillAlpha: 0.22, rect: { x: cutX, y: 22, w: 5, h: 212 } }),
                layer('overlay', { d: rectPath(22, cutY, 212, 5), fill: '#000000', fillAlpha: 0.22, rect: { x: 22, y: cutY, w: 212, h: 5 } }),
                layer('overlay', { d: rectPath(172, 34, 54, 56), fill: '#FFFFFF', fillAlpha: 0.18, rect: { x: 172, y: 34, w: 54, h: 56 } }),
                layer('overlay', { d: rectPath(34, 34, 108, 56), fill: '#FFFFFF', fillAlpha: 0.14, rect: { x: 34, y: 34, w: 108, h: 56 } }),
                layer('overlay', { d: rectPath(172, 126, 54, 98), fill: '#FFFFFF', fillAlpha: 0.10, rect: { x: 172, y: 126, w: 54, h: 98 } })
            ],
            glyph: { inset: 0.30, pivotX: 90, pivotY: 172 }
        };
    },

    /* 12 — Dark Academia: engraved, not printed. Double frame, hatch, letterpress glyph. */
    ENGRAVED_PLATE(c) {
        const plate = roundRectPath(18, 18, 220, 220, 10);
        const layers = [layer('plate', { d: plate, fill: c, rect: { x: 18, y: 18, w: 220, h: 220 } })];
        for (let i = 0; i < 7; i++) {
            const o = -80 + i * 46;
            layers.push(layer('overlay', {
                d: `M${18 + o},238L${138 + o},18`, stroke: '#000000', strokeWidth: 4,
                fill: '#000000', fillAlpha: 0.14,
                rect: { x: 18, y: 18, w: 220, h: 220 }
            }));
        }
        layers.push(layer('frame', { d: roundRectPath(30, 30, 196, 196, 6), stroke: '#FFFFFF', strokeWidth: 3, rect: { x: 30, y: 30, w: 196, h: 196 } }));
        layers.push(layer('frame', { d: roundRectPath(42, 42, 172, 172, 4), stroke: '#FFFFFF', strokeWidth: 2, rect: { x: 42, y: 42, w: 172, h: 172 } }));
        layers.push(layer('decor', {
            d: `${rectPath(26, 26, 12, 3)}${rectPath(26, 26, 3, 12)}${rectPath(218, 227, 12, 3)}${rectPath(227, 218, 3, 12)}`,
            fill: '#FFFFFF', fillAlpha: 0.55, rect: { x: 218, y: 26, w: 12, h: 12 }
        }));
        return {
            grammar: 'ENGRAVED_PLATE',
            guardBox: { x: 86, y: 86, w: 84, h: 84 },
            platePath: plate,
            layers,
            glyph: { inset: 0.52, letterpress: true }
        };
    },

    /* 13 — Vibrant Duotone: two-tone blocks with a split-colour overlap. The plate splits on
     * the diagonal and the glyph is rebuilt as cells, each taking the half it falls in; cells
     * straddling the split are drawn in the *other* tone, which is the overlap. */
    DUOTONE_SPLIT(c, ctx, tone) {
        const n = 14;
        const box = { x: 48, y: 48, w: 160, h: 160 };
        const cell = box.w / n;
        const grid = ctx.gridFor(box, n);
        const a = [], b = [], band = [];
        for (let gy = 0; gy < n; gy++) {
            const ra = [], rb = [], ro = [];
            for (let gx = 0; gx < n; gx++) {
                const v = grid[gy][gx];
                const cx = gx * cell + cell / 2;
                const cy = gy * cell + cell / 2;
                const above = cx + (box.h - cy) > box.w;
                const edge = Math.abs(cx + (box.h - cy) - box.w) < cell * 1.1;
                ra.push(v >= 0.28 && !above);
                rb.push(v >= 0.28 && above && !edge);
                ro.push(v >= 0.28 && edge);
            }
            a.push(ra); b.push(rb); band.push(ro);
        }
        const plate = roundRectPath(18, 18, 220, 220, 26);
        const lower = polyPath([[18, 238], [18, 18], [238, 238]]);
        const upper = polyPath([[238, 18], [238, 238], [18, 18]]);
        const rect = { x: box.x, y: box.y, w: box.w, h: box.h };
        return {
            grammar: 'DUOTONE_SPLIT',
            guardBox: box,
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 18, y: 18, w: 220, h: 220 } }),
                layer('overlay', { d: upper, fill: tone[0], solid: true, fillAlpha: 1, rect: { x: 18, y: 18, w: 220, h: 220 } }),
                ...pixelRunPaths(a, n, box.x, box.y, cell).map((d) => layer('ink', { d, fill: '#FFFFFF', rect })),
                ...pixelRunPaths(b, n, box.x, box.y, cell).map((d) => layer('ink', { d, fill: '#FFFFFF', fillAlpha: 0.94, rect })),
                // The overlap cells straddle both halves: a half-tone band that reads against
                // either side is the "split-colour overlap" the identity asks for.
                ...pixelRunPaths(band, n, box.x, box.y, cell).map((d) => layer('ink', { d, fill: '#FFFFFF', fillAlpha: 0.55, rect }))
            ],
            glyph: { baked: true },
            grid: { n, box }
        };
    },

    /* 14 — Aurora: a wavy multi-hue field. Vertical gradient plate, three crossed waves. */
    WAVE_FIELD(c, ctx, tone) {
        const plate = roundRectPath(12, 12, 232, 232, 64);
        return {
            grammar: 'WAVE_FIELD',
            guardBox: { x: 86, y: 86, w: 84, h: 84 },
            platePath: plate,
            plateGradient: [
                { offset: 0, color: c },
                { offset: 0.55, color: tone[0] },
                { offset: 1, color: tone[1] }
            ],
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 12, y: 12, w: 232, h: 232 } }),
                layer('overlay', { d: wavePath(12, 232, 92, 20, 0), stroke: '#FFFFFF', strokeWidth: 12, fill: '#FFFFFF', fillAlpha: 0.20, rect: { x: 12, y: 62, w: 232, h: 60 } }),
                layer('overlay', { d: wavePath(12, 232, 148, 24, 1.6), stroke: '#000000', strokeWidth: 14, fill: '#000000', fillAlpha: 0.20, rect: { x: 12, y: 112, w: 232, h: 72 } }),
                layer('overlay', { d: wavePath(12, 232, 212, 16, 3.1), stroke: '#FFFFFF', strokeWidth: 10, fill: '#FFFFFF', fillAlpha: 0.18, rect: { x: 12, y: 182, w: 232, h: 60 } })
            ],
            glyph: { inset: 0.50 }
        };
    },

    /* 15 — Emulate OS Deep Dark: a sharp plate with one corner notched and a bright rule. */
    OLED_NOTCH(c) {
        const plate = notchedSquarePath(20, 20, 216, 216, 58);
        return {
            grammar: 'OLED_NOTCH',
            guardBox: { x: 84, y: 96, w: 88, h: 84 },
            platePath: plate,
            layers: [
                layer('plate', { d: plate, fill: c, rect: { x: 20, y: 20, w: 216, h: 216 } }),
                layer('overlay', { d: rectPath(20, 222, 216, 8), fill: '#FFFFFF', fillAlpha: 0.14, rect: { x: 20, y: 222, w: 216, h: 8 } }),
                layer('decor', { d: 'M178,20L236,78', stroke: '#FFFFFF', strokeWidth: 6, rect: { x: 178, y: 20, w: 58, h: 58 } }),
                layer('frame', { d: notchedSquarePath(30, 30, 196, 196, 50), stroke: '#FFFFFF', strokeWidth: 2, rect: { x: 30, y: 30, w: 196, h: 196 } })
            ],
            glyph: { inset: 0.54 }
        };
    }
};

/** Which themes need a second (or third) tone for their grammar. */
export function needsTones(theme) {
    return ['MESH_BLOB', 'WAVE_FIELD', 'DUOTONE_SPLIT'].includes(theme.grammar);
}
