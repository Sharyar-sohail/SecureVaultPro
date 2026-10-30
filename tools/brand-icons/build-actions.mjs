#!/usr/bin/env node
/**
 * House action-glyph builder.
 *
 * Produces the `ic_a_*` family: one custom glyph for every semantic in the frozen
 * inventory (sync_engine/material_glyph_inventory.txt), drawn on the house grid defined
 * in DESIGN-SPEC.md. Also writes:
 *   - an SVG mirror of every glyph             -> tools/brand-icons/svg/
 *   - the typed Kotlin accessor                 -> presentation/components/VaultGlyph.kt
 *   - the generated guard test                  -> test/.../ActionGlyphSetTest.kt
 *   - the five dock silhouettes                 -> drawable/ic_dock_*
 *   - a manifest carrying the provenance table  -> tools/brand-icons/action-glyph-manifest.json
 *
 * Provenance is recorded per glyph in GLYPHS below: `iconoir:<weight>:<slug>` means the
 * geometry is derived from the MIT Iconoir pack and restyled here (house weight, live-area
 * fit, optical centring, round terminals), `house:<key>` means the path data is authored
 * here. Nothing ships as a Material glyph and nothing ships as un-restyled pack art.
 *
 * Run: node build-actions.mjs
 */

import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, '..', '..');
const vendorDir = join(here, 'vendor');
const svgOutDir = join(here, 'svg');
const drawableDir = join(repoRoot, 'app', 'src', 'main', 'res', 'drawable');
const componentsDir = join(
    repoRoot, 'app', 'src', 'main', 'java', 'com', 'securevault', 'pro', 'presentation', 'components'
);
const testDir = join(
    repoRoot, 'app', 'src', 'test', 'java', 'com', 'securevault', 'pro', 'presentation', 'components'
);
const inventoryFile = join(repoRoot, 'sync_engine', 'material_glyph_inventory.txt');

/* =========================================================================
 * HOUSE GRID — these constants are DESIGN-SPEC.md, made executable.
 * ======================================================================= */
const BOX = 24;          // the box every glyph is drawn in
const LIVE = 20;         // live area: art fits inside 20x20, centred on 12,12
const W_STEADY = 2;      // House Steady — default stroke weight
const W_DETAIL = 1.7;    // House Detail — for glyphs that fuse at 2
const CAP = 'round';     // terminals
const JOIN = 'round';    // joins
const DETAIL_PATHS = 8;  // >= this many sub-strokes trips the weight down
const INK = '#FF000000';       // neutral ink — the family is always tinted at the call site
const NO_FILL = '#00000000';

const snake = (name) => name
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
    .toLowerCase();
const camel = (name) => name.charAt(0).toLowerCase() + name.slice(1);

/**
 * `Public` camelCases to `public`, which the Kotlin parser refuses as an enum entry - it reads the
 * line as a visibility modifier and then fails at the previous comma. Renamed here rather than
 * special-cased in the generated file, so the manifest and the sweep mapper keep one source.
 */
const KOTLIN_RENAMED = { public: 'publicGlobe' };
const entryName = (semantic) => {
    const c = camel(semantic);
    return KOTLIN_RENAMED[c] ?? c;
};
const assetName = (semantic) => `ic_a_${snake(semantic)}`;

function readInventory() {
    return readFileSync(inventoryFile, 'utf8')
        .split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

/* =========================================================================
 * PATH ALGEBRA
 *
 * Restyling a stroke glyph onto the house grid is one affine: uniform scale about
 * (12,12) plus a re-centring translate, applied to every coordinate while the stroke
 * weight stays on the house constant. Uniform scale is deliberate — it keeps arc rx/ry
 * valid, so the sweep flags and the x-axis rotation pass through untouched.
 * ======================================================================= */
const NUM = /-?\d*\.?\d+(?:[eE][-+]?\d+)?/g;
const ARGC = { m: 2, l: 2, h: 1, v: 1, t: 2, s: 4, q: 4, c: 6, a: 7, z: 0 };

/** Split a path into [{cmd, rel, args[]}] groups, one per repeated argument block. */
function parseSegments(d) {
    const segs = [];
    for (const m of d.matchAll(/([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)/g)) {
        const cmd = m[1];
        const lower = cmd.toLowerCase();
        const n = ARGC[lower];
        if (n === undefined) throw new Error(`unsupported path command "${cmd}" in ${d}`);
        const nums = (m[2].match(NUM) || []).map(Number);
        if (lower === 'z') { segs.push({ cmd, lower, rel: false, args: [] }); continue; }
        if (nums.length % n !== 0) throw new Error(`ragged ${cmd} group in ${d}`);
        for (let i = 0; i < nums.length; i += n) {
            // Uppercase is absolute, lowercase is relative.
            segs.push({ cmd, lower, rel: cmd === lower, args: nums.slice(i, i + n) });
        }
    }
    if (!segs.length) throw new Error(`no path segments in ${d}`);
    return segs;
}

/** Bounding box over the on-curve points only — bezier handles never inflate it. */
function bbox(d) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    let x = 0, y = 0;
    const bump = (px, py) => {
        minX = Math.min(minX, px); maxX = Math.max(maxX, px);
        minY = Math.min(minY, py); maxY = Math.max(maxY, py);
    };
    for (const s of parseSegments(d)) {
        let px = x, py = y;
        if (s.lower === 'h') { px = s.rel ? x + s.args[0] : s.args[0]; }
        else if (s.lower === 'v') { py = s.rel ? y + s.args[0] : s.args[0]; }
        else if (s.lower === 'a') {
            px = s.rel ? x + s.args[5] : s.args[5];
            py = s.rel ? y + s.args[6] : s.args[6];
        } else if (s.lower !== 'z') {
            const n = s.args.length;
            px = s.rel ? x + s.args[n - 2] : s.args[n - 2];
            py = s.rel ? y + s.args[n - 1] : s.args[n - 1];
        }
        bump(px, py);
        x = px; y = py;
    }
    return { minX, minY, maxX, maxY, w: maxX - minX, h: maxY - minY };
}

const fmt = (n) => {
    const r = Math.round(n * 1000) / 1000;
    return Object.is(r, -0) ? '0' : String(r);
};

/**
 * Apply the house fit to one path. Absolute coordinates rotate about (12,12) and pick up
 * the centreing offset; relative ones are pure deltas, so only the scale applies to them.
 */
function fitPath(d, scale, dx, dy) {
    const out = [];
    for (const s of parseSegments(d)) {
        if (s.lower === 'z') { out.push('Z'); continue; }
        const a = [...s.args];
        const rel = (v) => v * scale;
        if (s.lower === 'a') {
            a[0] *= scale; a[1] *= scale;              // rx, ry: uniform, so still valid
            // rot, largeArcFlag, sweepFlag pass through untouched
            a[5] = s.rel ? rel(a[5]) : (a[5] - 12) * scale + 12 + dx;
            a[6] = s.rel ? rel(a[6]) : (a[6] - 12) * scale + 12 + dy;
        } else if (s.lower === 'h') {
            a[0] = s.rel ? rel(a[0]) : (a[0] - 12) * scale + 12 + dx;
        } else if (s.lower === 'v') {
            a[0] = s.rel ? rel(a[0]) : (a[0] - 12) * scale + 12 + dy;
        } else {
            for (let k = 0; k < a.length; k += 2) {
                a[k] = s.rel ? rel(a[k]) : (a[k] - 12) * scale + 12 + dx;
                a[k + 1] = s.rel ? rel(a[k + 1]) : (a[k + 1] - 12) * scale + 12 + dy;
            }
        }
        out.push(s.cmd + a.map(fmt).join(' '));
    }
    return out.join('');
}

/**
 * Fit a whole glyph — base geometry and any house overlay marks together — into the live
 * area and centre it optically. `fit: 'none'` opts a member out.
 */
function layoutGlyph(paths, doFit) {
    if (!doFit) return paths.map((p) => p.d);
    const box = paths.map((p) => bbox(p.d)).reduce((a, b) => ({
        minX: Math.min(a.minX, b.minX), minY: Math.min(a.minY, b.minY),
        maxX: Math.max(a.maxX, b.maxX), maxY: Math.max(a.maxY, b.maxY)
    }));
    const span = Math.max(box.maxX - box.minX, box.maxY - box.minY);
    const scale = span > LIVE ? LIVE / span : 1;      // never blow small art up
    const dx = (12 - (box.minX + box.maxX) / 2) * scale;
    const dy = (12 - (box.minY + box.maxY) / 2) * scale;
    return paths.map((p) => fitPath(p.d, scale, dx, dy));
}

/* =========================================================================
 * VENDOR READING
 * ======================================================================= */
function readIconoir(weight, slug) {
    const file = join(vendorDir, 'iconoir', weight, `${slug}.svg`);
    if (!existsSync(file)) throw new Error(`missing vendored Iconoir glyph: iconoir/${weight}/${slug}.svg`);
    const svg = readFileSync(file, 'utf8');
    const root = /<svg\b[^>]*>/.exec(svg)?.[0] ?? '';
    const rootFill = /\sfill="([^"]+)"/.exec(root)?.[1];
    const paths = [];
    for (const m of svg.matchAll(/<path\b([^>]*?)\/?>/g)) {
        const d = /\sd="([^"]+)"/.exec(m[1])?.[1];
        if (!d) continue;
        const fill = /\sfill="([^"]+)"/.exec(m[1])?.[1];
        // A path that fills currentColor while the root fills none is solid art.
        paths.push({ d, solid: !!fill && fill !== 'none' && fill !== rootFill });
    }
    if (!paths.length) throw new Error(`no <path> in ${file}`);
    return paths;
}

/* =========================================================================
 * HOUSE-DRAWN GEOMETRY (authored on the 24 grid, live area 2..22)
 * ======================================================================= */
const HOUSE = {
    backspace: [
        { d: 'M21 5.5H9.8C9.3 5.5 8.85 5.72 8.55 6.1L3.1 11.7C2.68 12.15 2.68 12.85 3.1 13.3L8.55 18.9C8.85 19.28 9.3 19.5 9.8 19.5H21C21.55 19.5 22 19.05 22 18.5V6.5C22 5.95 21.55 5.5 21 5.5Z' },
        { d: 'M11.6 9.4L16.1 14.6' },
        { d: 'M16.1 9.4L11.6 14.6' }
    ],
    folderOpen: [
        { d: 'M2.9 18.7V6.4C2.9 5.6 3.55 5 4.35 5H9.05L11.15 7.4H19.55C20.35 7.4 21 8.05 21 8.85V11.2' },
        { d: 'M2.35 18.7L4.95 11.75C5.15 11.2 5.65 10.85 6.25 10.85H21.4C22.15 10.85 22.6 11.6 22.25 12.25L19.85 18.55C19.62 19.1 19.1 19.5 18.5 19.5H3.35C2.8 19.5 2.4 19.15 2.35 18.7Z' }
    ],
    locationOn: [
        { d: 'M20 10C20 14.4183 12 22 12 22C12 22 4 14.4183 4 10C4 5.58172 7.58172 2 12 2C16.4183 2 20 5.58172 20 10Z' },
        { d: 'M12 7.6C13.3255 7.6 14.4 8.6745 14.4 10C14.4 11.3255 13.3255 12.4 12 12.4C10.6745 12.4 9.6 11.3255 9.6 10C9.6 8.6745 10.6745 7.6 12 7.6Z', solid: true }
    ],
    pictureAsPdf: [
        { d: 'M4 21.4V2.6C4 2.2686 4.2686 2 4.6 2H16.25C16.41 2 16.56 2.06 16.68 2.18L19.82 5.32C19.94 5.44 20 5.59 20 5.75V21.4C20 21.73 19.73 22 19.4 22H4.6C4.27 22 4 21.73 4 21.4Z' },
        { d: 'M16 2V5.4C16 5.73 16.27 6 16.6 6H20' },
        { d: 'M7.8 11.6H16.2V17.2H7.8Z' },
        { d: 'M10.1 13.2C10.6523 13.2 11.1 13.6477 11.1 14.2C11.1 14.7523 10.6523 15.2 10.1 15.2C9.5477 15.2 9.1 14.7523 9.1 14.2C9.1 13.6477 9.5477 13.2 10.1 13.2Z', solid: true }
    ],
    stop: [
        { d: 'M7.4 5.6H16.6C17.5941 5.6 18.4 6.4059 18.4 7.4V16.6C18.4 17.5941 17.5941 18.4 16.6 18.4H7.4C6.4059 18.4 5.6 17.5941 5.6 16.6V7.4C5.6 6.4059 6.4059 5.6 7.4 5.6Z', solid: true }
    ],
    systemUpdate: [
        { d: 'M12 15.6V4.6' },
        { d: 'M7.2 9.4L12 4.6L16.8 9.4' },
        { d: 'M4.6 19.4H19.4' }
    ],
    flashOn: [
        { d: 'M12.6 9.6V3.6L6.3 13.2H11.3V19.6L17.6 10H12.6Z' },
        { d: 'M19.6 4.2L21 3' },
        { d: 'M4.4 4.2L3 3' }
    ],
    vibration: [
        { d: 'M8.4 19.1V4.9C8.4 4.5 8.7 4.2 9.1 4.2H14.9C15.3 4.2 15.6 4.5 15.6 4.9V19.1C15.6 19.5 15.3 19.8 14.9 19.8H9.1C8.7 19.8 8.4 19.5 8.4 19.1Z' },
        { d: 'M11.4 17.1H12.6' },
        { d: 'M5.9 9C5 10.5 5 13.5 5.9 15' },
        { d: 'M3.2 7.2C1.8 9.7 1.8 14.3 3.2 16.8' },
        { d: 'M18.1 9C19 10.5 19 13.5 18.1 15' },
        { d: 'M20.8 7.2C22.2 9.7 22.2 14.3 20.8 16.8' }
    ]
};

/** House marks added to a derived glyph to pull a Material synonym apart. */
const OVERLAYS = {
    warningAmber: [{ d: 'M12 8.3V12.3' }, { d: 'M12 15.3H12.01' }],
    errorOutline: [{ d: 'M12 7.7V12.3' }, { d: 'M12 15.4H12.01' }]
};

/* =========================================================================
 * THE FAMILY — one row per frozen semantic. `note` records why a synonym got
 * its own device, or why pack art was not good enough and the glyph is house-drawn.
 * ======================================================================= */
const GLYPHS = {
    AccountBalance: { src: 'iconoir:regular:bank' },
    Add: { src: 'iconoir:regular:plus' },
    ArrowBack: { src: 'iconoir:regular:arrow-left', note: 'straight shank, not Material’s tailed back arrow' },
    ArrowForward: { src: 'iconoir:regular:arrow-right' },
    ArrowUpward: { src: 'iconoir:regular:arrow-up' },
    AttachFile: { src: 'iconoir:regular:attachment' },
    AutoAwesome: { src: 'iconoir:regular:spark', note: 'four-point sparkle, kept apart from the wand' },
    AutoFixHigh: { src: 'iconoir:regular:magic-wand' },
    Backspace: { src: 'house:backspace', note: 'no pack delete-key; left tag with a crossbar' },
    Badge: { src: 'iconoir:regular:user-badge-check' },
    Bolt: { src: 'iconoir:regular:flash', note: 'bare bolt; FlashOn adds the rays' },
    BrokenImage: { src: 'iconoir:regular:media-image-xmark' },
    CalendarToday: { src: 'iconoir:regular:calendar' },
    Call: { src: 'iconoir:regular:phone', note: 'the handset; Phone takes the dial pad' },
    CameraAlt: { src: 'iconoir:regular:camera' },
    Chat: { src: 'iconoir:regular:chat-bubble', note: 'empty bubble; Message is the ruled one' },
    Check: { src: 'iconoir:regular:check' },
    CheckCircle: { src: 'iconoir:regular:check-circle' },
    ChevronLeft: { src: 'iconoir:regular:nav-arrow-left' },
    ChevronRight: { src: 'iconoir:regular:nav-arrow-right' },
    Close: { src: 'iconoir:regular:xmark' },
    Cloud: { src: 'iconoir:regular:cloud' },
    CloudDone: { src: 'iconoir:regular:cloud-check' },
    CloudDownload: { src: 'iconoir:regular:cloud-download' },
    CloudOff: { src: 'iconoir:regular:cloud-xmark' },
    CloudUpload: { src: 'iconoir:regular:cloud-upload' },
    Contactless: { src: 'iconoir:regular:contactless' },
    Contacts: { src: 'iconoir:regular:user-circle', note: 'ringed head; Person is the bare one' },
    ContentCopy: { src: 'iconoir:regular:copy' },
    ContentPaste: { src: 'iconoir:regular:paste-clipboard' },
    CreditCard: { src: 'iconoir:regular:credit-card' },
    CropFree: { src: 'iconoir:regular:crop', note: 'free crop brackets, no guide lines' },
    CurrencyBitcoin: { src: 'iconoir:regular:bitcoin-circle' },
    DarkMode: { src: 'iconoir:regular:half-moon' },
    Delete: { src: 'iconoir:regular:trash' },
    Description: { src: 'iconoir:regular:page', note: 'ruled document; InsertDriveFile is the blank one' },
    DirectionsCar: { src: 'iconoir:regular:car' },
    DocumentScanner: { src: 'iconoir:regular:scanning', note: 'frame with the scan bar mid-travel' },
    DoneAll: { src: 'iconoir:regular:double-check' },
    Download: { src: 'iconoir:regular:download', note: 'arrow to the rule; the file members add a tray' },
    Edit: { src: 'iconoir:regular:edit-pencil' },
    Email: { src: 'iconoir:regular:mail' },
    ErrorOutline: { src: 'iconoir:regular:xmark-circle', note: 'ring plus the cross, Material ErrorOutline semantics' },
    ExpandLess: { src: 'iconoir:regular:nav-arrow-up', note: 'plain chevron; ArrowUpward keeps the shank' },
    ExpandMore: { src: 'iconoir:regular:nav-arrow-down' },
    FileDownload: { src: 'iconoir:regular:download-square', note: 'tray device, set apart from plain Download' },
    FileUpload: { src: 'iconoir:regular:upload-square', note: 'tray device, set apart from plain Upload' },
    Fingerprint: { src: 'iconoir:regular:fingerprint' },
    FlashOn: { src: 'house:flashOn', note: 'torch with rays, so it is not a second copy of Bolt' },
    Folder: { src: 'iconoir:regular:folder' },
    FolderOpen: { src: 'house:folderOpen', note: 'no pack open folder; drawn to match Folder’s tab' },
    GridOn: { src: 'iconoir:regular:view-grid' },
    HealthAndSafety: { src: 'iconoir:regular:health-shield' },
    Home: { src: 'iconoir:regular:home' },
    Image: { src: 'iconoir:regular:media-image' },
    Info: { src: 'iconoir:regular:info-circle' },
    InsertDriveFile: { src: 'iconoir:regular:empty-page' },
    Insights: { src: 'iconoir:regular:stats-report' },
    Key: { src: 'iconoir:regular:key' },
    Language: { src: 'iconoir:regular:translate', note: 'the A/文 device; Public keeps the globe' },
    LightMode: { src: 'iconoir:regular:sun-light' },
    Link: { src: 'iconoir:regular:link' },
    LocationOn: { src: 'house:locationOn', note: 'marker carrying the lit dot; Pin is hollow' },
    Lock: { src: 'iconoir:regular:lock' },
    Memory: { src: 'iconoir:regular:cpu' },
    Message: { src: 'iconoir:regular:chat-lines', note: 'ruled bubble; Chat is the empty one' },
    Mic: { src: 'iconoir:regular:microphone' },
    MusicNote: { src: 'iconoir:regular:music-note' },
    Notes: { src: 'iconoir:regular:notes' },
    OpenInNew: { src: 'iconoir:regular:open-new-window' },
    Palette: { src: 'iconoir:regular:palette' },
    Person: { src: 'iconoir:regular:user' },
    Phone: { src: 'iconoir:regular:dialpad', note: 'the number pad, so it is not a second handset' },
    PhotoLibrary: { src: 'iconoir:regular:media-image-list', note: 'stacked frames; Image is the single one' },
    PictureAsPdf: { src: 'house:pictureAsPdf', note: 'a page carrying an image tile' },
    Pin: { src: 'iconoir:regular:map-pin', note: 'hollow marker; LocationOn adds the dot' },
    PlayArrow: { src: 'iconoir:regular:play' },
    Public: { src: 'iconoir:regular:globe' },
    PushPin: { src: 'iconoir:regular:pin', note: 'the thumbtack, which is what a push pin is' },
    QrCode: { src: 'iconoir:regular:qr-code' },
    QrCodeScanner: { src: 'iconoir:regular:scan-qr-code', note: 'QR inside the capture corners' },
    Refresh: { src: 'iconoir:regular:refresh', note: 'one circular arrow; Sync is the pair' },
    Replay: { src: 'iconoir:regular:restart', note: 'the rewind loop, distinct from Refresh' },
    Search: { src: 'iconoir:regular:search' },
    Security: { src: 'iconoir:regular:shield-check', note: 'Shield is the bare one' },
    Send: { src: 'iconoir:regular:send' },
    Settings: { src: 'iconoir:regular:settings' },
    Share: { src: 'iconoir:regular:share-android', note: 'three-node share, closest to the semantic' },
    Shield: { src: 'iconoir:regular:shield' },
    Shuffle: { src: 'iconoir:regular:shuffle' },
    Star: { src: 'iconoir:solid:star', note: 'the filled half of the Star / StarBorder pair' },
    StarBorder: { src: 'iconoir:regular:star', note: 'the outlined half of the pair' },
    Stop: { src: 'house:stop', note: 'solid square — the meaning is mass, not outline' },
    Sync: { src: 'iconoir:regular:refresh-double' },
    SystemUpdate: { src: 'house:systemUpdate', note: 'arrow into the device rule' },
    TextFields: { src: 'iconoir:regular:text' },
    Timer: { src: 'iconoir:regular:timer' },
    TravelExplore: { src: 'iconoir:regular:compass', note: 'Public is the globe; this is the bearing' },
    Upload: { src: 'iconoir:regular:upload' },
    UploadFile: { src: 'iconoir:regular:upload-data-window', note: 'the data window, third upload member' },
    Vibration: { src: 'house:vibration', note: 'no pack vibrate; device plus both wave arcs' },
    Videocam: { src: 'iconoir:regular:video-camera' },
    Visibility: { src: 'iconoir:regular:eye' },
    VisibilityOff: { src: 'iconoir:regular:eye-closed' },
    VolumeUp: { src: 'iconoir:regular:sound-high' },
    Warning: { src: 'iconoir:regular:warning-triangle' },
    WarningAmber: { src: 'iconoir:regular:warning-hexagon', note: 'hazard plate, so the two cautions stay apart' }
};

/* =========================================================================
 * COMPOSE
 * ======================================================================= */
function composeGlyph(semantic, spec) {
    let base, slug;
    if (spec.src.startsWith('house:')) {
        slug = spec.src.slice(6);
        base = HOUSE[camel(slug)];
        if (!base) throw new Error(`no house geometry for ${spec.src}`);
        base = base.map((p) => ({ ...p }));
    } else {
        const m = /^iconoir:(regular|solid):(.+)$/.exec(spec.src);
        if (!m) throw new Error(`bad src "${spec.src}" for ${semantic}`);
        slug = `${m[1]}/${m[2]}`;
        base = readIconoir(m[1], m[2]);
    }
    const paths = base.concat((OVERLAYS[camel(semantic)] ?? []).map((o) => ({ ...o })));
    const fitted = layoutGlyph(paths, spec.fit !== 'none');
    const lines = paths.filter((p) => !p.solid);
    const weight = spec.weight ?? (lines.length >= DETAIL_PATHS ? W_DETAIL : W_STEADY);
    return { paths, d: fitted, weight, slug };
}

function renderVector(semantic, g) {
    const body = g.paths.map((p, i) => {
        // Solid members keep the stroke as well as the fill: that is how Iconoir draws
        // them, and dropping it would make the filled half of a pair read lighter.
        return p.solid
            ? `    <path
        android:pathData="${g.d[i]}"
        android:fillColor="${INK}"
        android:strokeColor="${INK}"
        android:strokeWidth="${g.weight}"
        android:strokeLineCap="${CAP}"
        android:strokeLineJoin="${JOIN}" />`
            : `    <path
        android:pathData="${g.d[i]}"
        android:fillColor="${NO_FILL}"
        android:strokeColor="${INK}"
        android:strokeWidth="${g.weight}"
        android:strokeLineCap="${CAP}"
        android:strokeLineJoin="${JOIN}" />`;
    }).join('\n');
    const prov = g.slug.startsWith('regular/') || g.slug.startsWith('solid/')
        ? `derived from Iconoir ${g.slug} (MIT), restyled to the house grid`
        : `drawn to the house grid`;
    return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/brand-icons/build-actions.mjs - do not edit.
     House action glyph | Semantic: ${semantic} | Provenance: ${prov}
     Grid: ${BOX}dp box, ${LIVE}dp live area, ${CAP}/${JOIN} terminals, ${g.weight}dp stroke.
     Ink is neutral: every caller tints through VaultColor. See DESIGN-SPEC.md. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${BOX}dp"
    android:height="${BOX}dp"
    android:viewportWidth="${BOX}"
    android:viewportHeight="${BOX}">
${body}
</vector>
`;
}

function renderSvg(semantic, g) {
    const body = g.paths.map((p, i) => p.solid
        ? `  <path d="${g.d[i]}" fill="currentColor" stroke="currentColor" stroke-width="${g.weight}" stroke-linecap="${CAP}" stroke-linejoin="${JOIN}"/>`
        : `  <path d="${g.d[i]}" fill="none" stroke="currentColor" stroke-width="${g.weight}" stroke-linecap="${CAP}" stroke-linejoin="${JOIN}"/>`
    ).join('\n');
    return `<svg width="${BOX}" height="${BOX}" viewBox="0 0 ${BOX} ${BOX}" xmlns="http://www.w3.org/2000/svg"><!-- ${semantic}: ${g.slug} -->
${body}
</svg>
`;
}

/* =========================================================================
 * DOCK ART — one silhouette per DockStyle in presentation/theme/Palette.kt
 * ======================================================================= */
function roundRectPath(x, y, w, h, r) {
    const rr = Math.max(0, Math.min(r, Math.min(w, h) / 2));
    if (rr === 0) return `M${x},${y}H${x + w}V${y + h}H${x}Z`;
    return `M${x + rr},${y}H${x + w - rr}A${rr},${rr} 0 0 1 ${x + w},${y + rr}V${y + h - rr}` +
        `A${rr},${rr} 0 0 1 ${x + w - rr},${y + h}H${x + rr}A${rr},${rr} 0 0 1 ${x},${y + h - rr}` +
        `V${y + rr}A${rr},${rr} 0 0 1 ${x + rr},${y}Z`;
}
const grad = (name, d) => ({ kind: 'gradient', name, d });
const stroke = (d, sw, tone) => ({ kind: 'stroke', d, sw, tone });
const fill = (d, tone) => ({ kind: 'fill', d, tone });

function wells(x, y, w, h, n) {
    const pad = 16, gap = 12;
    const cw = (w - pad * 2 - gap * (n - 1)) / n;
    return Array.from({ length: n }, (_, i) =>
        fill(roundRectPath(x + pad + i * (cw + gap), y + h - 30, cw, 22, 11), 'well'));
}

const DOCK = [
    {
        id: 'minimal_bar', label: 'Minimal bar', w: 360, h: 64,
        blurb: 'Edge-to-edge plate with a hairline top rule and no float — the bar is the theme.',
        build: ({ w, h }) => [grad('body', roundRectPath(0, 0, w, h, 0)), stroke(`M0 1.5H${w}`, 1.5, 'rim')]
    },
    {
        id: 'soft_floating', label: 'Soft floating', w: 360, h: 76,
        blurb: 'Bar lifted off both edges, 20dp corner, with a cast shadow lip under it.',
        build: ({ w, h }) => [
            fill(roundRectPath(14, 12, w - 28, h - 12, 22), 'shadow'),
            grad('body', roundRectPath(14, 8, w - 28, h - 14, 20)),
            stroke(roundRectPath(14, 8, w - 28, h - 14, 20), 1.25, 'rim')
        ]
    },
    {
        id: 'pill_frosted', label: 'Pill frosted', w: 300, h: 72,
        blurb: 'Frosted capsule: full round, inner rim, and a sheen raked across the glass.',
        build: ({ w, h }) => {
            const x = 30, y = 6, pw = 240, ph = 56, r = 28;
            return [
                grad('body', roundRectPath(x, y, pw, ph, r)),
                stroke(roundRectPath(x, y, pw, ph, r), 1.5, 'rim'),
                grad('sheen', `M${x + r},${y}H${x + pw - r}A${r},${r} 0 0 1 ${x + pw},${y + r}V${y + ph * 0.38}L${x},${y + ph * 0.72}V${y + r}A${r},${r} 0 0 1 ${x + r},${y}Z`)
            ];
        }
    },
    {
        id: 'comfort_panel', label: 'Comfort panel', w: 360, h: 92,
        blurb: 'Tall roomy panel, 26dp corner, with four wells cut for the item labels.',
        build: ({ w, h }) => [
            fill(roundRectPath(12, 10, w - 24, h - 10, 28), 'shadow'),
            grad('body', roundRectPath(12, 6, w - 24, h - 12, 26)),
            stroke(roundRectPath(12, 6, w - 24, h - 12, 26), 1.5, 'rim'),
            ...wells(12, 6, w - 24, h - 12, 4)
        ]
    },
    {
        id: 'flat_plate', label: 'Flat plate', w: 360, h: 68,
        blurb: 'Square-cut plate fused to the bottom edge: chamfered top corners, heavy rule.',
        build: ({ w, h }) => [
            grad('body', roundRectPath(0, 0, w, h, 0)),
            stroke(`M0 ${h}V8L8 0H${w - 8}L${w} 8V${h}`, 2, 'rim'),
            stroke(`M0 3.5H${w}`, 3, 'rule')
        ]
    }
];

// Dock ink roles are resolved at runtime through tint; the asset is authored in neutral
// greys so one silhouette works on a light or a dark surface.
const DOCK_TONES = {
    body: ['#FFFFFFFF', '#99FFFFFF'],
    sheen: ['#73FFFFFF', '#00FFFFFF'],
    rim: '#40FFFFFF',
    rule: '#33FFFFFF',
    shadow: '#24000000',
    well: '#14000000'
};

function renderDock(style) {
    const els = style.build(style);
    const body = els.map((e) => {
        if (e.kind === 'gradient') {
            const [from, to] = DOCK_TONES[e.name] ?? DOCK_TONES.body;
            return `    <path android:pathData="${e.d}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:startX="0"
                android:startY="0"
                android:endX="${style.w}"
                android:endY="${style.h}"
                android:type="linear">
                <item android:offset="0" android:color="${from}" />
                <item android:offset="1" android:color="${to}" />
            </gradient>
        </aapt:attr>
    </path>`;
        }
        if (e.kind === 'stroke') {
            return `    <path
        android:pathData="${e.d}"
        android:fillColor="${NO_FILL}"
        android:strokeColor="${DOCK_TONES[e.tone]}"
        android:strokeWidth="${e.sw}"
        android:strokeLineCap="${CAP}"
        android:strokeLineJoin="${JOIN}" />`;
        }
        return `    <path android:pathData="${e.d}" android:fillColor="${DOCK_TONES[e.tone]}" />`;
    }).join('\n');
    return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/brand-icons/build-actions.mjs - do not edit.
     Dock silhouette for DockStyle.${style.id.toUpperCase()} | ${style.blurb} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${style.w}dp"
    android:height="${style.h}dp"
    android:viewportWidth="${style.w}"
    android:viewportHeight="${style.h}">
${body}
</vector>
`;
}

function renderDockSvg(style) {
    const body = style.build(style).map((e) => {
        if (e.kind === 'gradient') {
            const [from] = DOCK_TONES[e.name] ?? DOCK_TONES.body;
            return `  <path d="${e.d}" fill="${from}"/>`;
        }
        if (e.kind === 'stroke') {
            return `  <path d="${e.d}" fill="none" stroke="${DOCK_TONES[e.tone]}" stroke-width="${e.sw}" stroke-linecap="${CAP}" stroke-linejoin="${JOIN}"/>`;
        }
        return `  <path d="${e.d}" fill="${DOCK_TONES[e.tone]}"/>`;
    }).join('\n');
    return `<svg width="${style.w}" height="${style.h}" viewBox="0 0 ${style.w} ${style.h}" xmlns="http://www.w3.org/2000/svg"><!-- ${style.label} -->
${body}
</svg>
`;
}

/* =========================================================================
 * KOTLIN
 * ======================================================================= */
function buildKotlin(inventory) {
    const rows = inventory.map((s) => {
        const a = assetName(s);
        return `    ${entryName(s)}("${a}", R.drawable.${a}),`;
    }).join('\n');
    const dockRows = DOCK.map((d) =>
        `        DockStyle.${d.id.toUpperCase()} -> R.drawable.ic_dock_${d.id}`).join('\n');
    return `package com.securevault.pro.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securevault.pro.R
import com.securevault.pro.presentation.theme.DockStyle
import com.securevault.pro.presentation.theme.VaultColor

/**
 * GENERATED by tools/brand-icons/build-actions.mjs - do not edit by hand.
 *
 * The house action-glyph family: one custom glyph for every semantic the app used to borrow
 * from Material. Drawn on the grid in tools/brand-icons/DESIGN-SPEC.md - ${BOX}dp box, ${LIVE}dp
 * live area, round terminals, one stroke weight per glyph - and shipped in a single neutral
 * ink, so the only things a caller decides are size and tint.
 *
 * The names are a frozen contract: one entry per line of sync_engine/material_glyph_inventory.txt,
 * camelCase of the Material semantic, resolving to the matching ic_a_* drawable. ActionGlyphSetTest
 * fails the build if a drawable or an entry goes missing, which is the tripwire that keeps a
 * borrowed Material mark from creeping back.
 */
enum class Glyph(val asset: String, @DrawableRes val drawableId: Int) {
${rows}
}

/**
 * Draws one house glyph.
 *
 * Tint defaults to [VaultColor.textBody]: a glyph sitting in a row of body copy has to survive
 * the body-contrast rule the theme already enforces. Anything that carries meaning passes the
 * matching role instead - [VaultColor.credit], [VaultColor.debit], [VaultColor.warning].
 *
 * The glyph crossfades when the value it stands for flips (send state, visibility toggle, sync
 * status), so a state change reads as a move rather than a flicker. Pass animate = false inside
 * lazily-composed lists, where the crossfade would fight the scroll.
 */
@Composable
fun VaultGlyph(
    glyph: Glyph,
    size: Dp = ${BOX}.dp,
    tint: Color = VaultColor.textBody,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (animate) {
            Crossfade(targetState = glyph, label = "vaultGlyph") { current ->
                GlyphImage(current, size, tint, contentDescription)
            }
        } else {
            GlyphImage(glyph, size, tint, contentDescription)
        }
    }
}

@Composable
private fun GlyphImage(
    glyph: Glyph,
    size: Dp,
    tint: Color,
    contentDescription: String?
) {
    Image(
        painter = painterResource(glyph.drawableId),
        contentDescription = contentDescription,
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint)
    )
}

/** The same glyph on a filled plate: chips, dock items, status badges. */
@Composable
fun VaultGlyphOnPlate(
    glyph: Glyph,
    plate: Color,
    size: Dp = 40.dp,
    corner: Dp = 12.dp,
    onPlate: Color = VaultColor.onAccent,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .background(plate, RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center
    ) {
        VaultGlyph(
            glyph = glyph,
            size = size * 0.55f,
            tint = onPlate,
            contentDescription = contentDescription,
            animate = false
        )
    }
}

/** The dock silhouettes, one per [DockStyle]. */
object DockArt {
    @DrawableRes
    fun background(dock: DockStyle): Int = when (dock) {
${dockRows}
    }
}
`;
}

/* =========================================================================
 * GUARD TEST (generated, so the contract cannot drift from the family)
 * ======================================================================= */
function buildGuardTest(inventory) {
    const names = inventory.map((s) => `        "${assetName(s)}",`).join('\n');
    return `package com.securevault.pro.presentation.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GENERATED by tools/brand-icons/build-actions.mjs - do not edit by hand.
 *
 * The Material icon ban only holds if something enforces it. Three things must stay in
 * lockstep and this is the tripwire: the frozen semantic inventory, the ic_a_* drawables on
 * disk, and the Glyph enum callers actually use. Add a Material symbol to the app without
 * drawing a glyph for it, or delete a drawable without deleting its entry, and this fails.
 */
class ActionGlyphSetTest {

    /** Every semantic that must carry a house glyph, as the frozen resource name. */
    private val expectedAssets = setOf(
${names}
    )

    private val semantics: Int get() = expectedAssets.size

    private fun drawableDir(): File {
        // A JVM unit test runs with the module directory as its working dir on some runners
        // and the repo root on others, so walk up until the drawable tree shows up.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 6) {
            val a = File(dir, "src/main/res/drawable")
            if (a.isDirectory) return a
            val b = File(dir, "app/src/main/res/drawable")
            if (b.isDirectory) return b
            dir = dir.parentFile
            hops++
        }
        return File("")
    }

    @Test
    fun everyInventorySemanticHasADrawableOnDisk() {
        val dir = drawableDir()
        assertTrue("could not locate app/src/main/res/drawable", dir.isDirectory)
        val missing = expectedAssets.filterNot { File(dir, it + ".xml").isFile }.sorted()
        assertTrue("missing house glyphs: " + missing, missing.isEmpty())
    }

    @Test
    fun everyGlyphEntryResolvesToADrawableThatExists() {
        val dir = drawableDir()
        val orphans = Glyph.entries.filterNot { File(dir, it.asset + ".xml").isFile }
        assertTrue("Glyph entries with no drawable on disk: " + orphans, orphans.isEmpty())
        val badNames = Glyph.entries.filterNot { it.asset.startsWith("ic_a_") }
        assertTrue("a glyph must resolve into the ic_a_* family: " + badNames, badNames.isEmpty())
    }

    @Test
    fun familyHasExactlyOneGlyphPerSemanticAndNoExtras() {
        assertEquals("the enum must carry one entry per frozen semantic", semantics, Glyph.entries.size)
        assertEquals("enum assets must be exactly the frozen set", expectedAssets, Glyph.entries.map { it.asset }.toSet())
        val onDisk = (drawableDir().listFiles { f ->
            f.name.startsWith("ic_a_") && f.name.endsWith(".xml")
        }?.map { it.name.removeSuffix(".xml") }?.toSet() ?: emptySet())
        assertEquals("drawables outside the frozen set: " + (onDisk - expectedAssets), expectedAssets, onDisk)
    }

    @Test
    fun enumNamesAreCamelCaseOfTheInventorySemantics() {
        val entries = Glyph.entries.map { it.name }.toSet()
        // Spot-check the awkward ones: acronyms must not gain a doubled underscore and
        // the multi-word members must all be present, because the sweep agent calls these.
        listOf(
            "qrCode", "qrCodeScanner", "checkCircle", "warningAmber", "autoFixHigh",
            "insertDriveFile", "pictureAsPdf", "contentCopy", "contactless", "doneAll",
            "cloudDone", "fileDownload", "uploadFile", "visibilityOff", "systemUpdate",
            "errorOutline", "arrowUpward", "healthAndSafety", "travelExplore", "currencyBitcoin"
        ).forEach { assertTrue("Glyph.$it must exist for the sweep", entries.contains(it)) }
    }

    /**
     * The ban itself. Nothing in the generated glyph layer may reach for Material icons, and
     * since this file is the only accessor callers need, a stray import shows up right here.
     */
    @Test
    fun theGlyphLayerDoesNotTouchMaterialIcons() {
        val dir = componentsDir()
        assertTrue("could not locate the components source tree", dir.isDirectory)
        val text = File(dir, "VaultGlyph.kt").readText()
        assertTrue("VaultGlyph.kt must not import Material icons",
            !text.contains("androidx.compose.material.icons"))
        assertTrue("DockArt must cover every DockStyle case",
            File(dir, "VaultGlyph.kt").readText().contains("DockStyle.FLAT_PLATE"))
    }

    /**
     * Restyling is what makes this a family instead of a grab-bag: every line member carries
     * the house round terminals, so a path that arrived without them was hand-pasted.
     */
    @Test
    fun everyLineGlyphCarriesTheHouseTerminals() {
        val dir = drawableDir()
        val offenders = expectedAssets.filter { name ->
            val text = File(dir, name + ".xml").takeIf { it.isFile }?.readText() ?: return@filter false
            text.contains("android:strokeWidth") && !text.contains("android:strokeLineCap=\\"round\\"")
        }
        assertTrue("glyphs missing the house round terminals: " + offenders, offenders.isEmpty())
    }

    @Test
    fun theDockHasArtForEveryStyle() {
        val dir = drawableDir()
        val styles = listOf("minimal_bar", "soft_floating", "pill_frosted", "comfort_panel", "flat_plate")
        val missing = styles.filterNot { File(dir, "ic_dock_" + it + ".xml").isFile }
        assertTrue("dock silhouettes missing: " + missing, missing.isEmpty())
    }

    private fun componentsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        val tail = "src/main/java/com/securevault/pro/presentation/components"
        var hops = 0
        while (dir != null && hops < 6) {
            val a = File(dir, tail)
            if (File(a, "VaultGlyph.kt").isFile) return a
            val b = File(dir, "app/" + tail)
            if (File(b, "VaultGlyph.kt").isFile) return b
            dir = dir.parentFile
            hops++
        }
        return File("")
    }
}
`;
}

/* =========================================================================
 * MAIN
 * ======================================================================= */
async function main() {
    const inventory = readInventory();
    const mapped = Object.keys(GLYPHS);

    const unmapped = inventory.filter((s) => !GLYPHS[s]);
    if (unmapped.length) throw new Error(`inventory semantics with no glyph: ${unmapped.join(', ')}`);
    const extra = mapped.filter((s) => !inventory.includes(s));
    if (extra.length) throw new Error(`GLYPHS rows not in the frozen inventory: ${extra.join(', ')}`);
    if (inventory.length !== mapped.length) {
        throw new Error(`inventory has ${inventory.length} semantics, GLYPHS has ${mapped.length}`);
    }
    const dupes = [...new Set(inventory.map(assetName)
        .filter((n, i, a) => a.indexOf(n) !== i))];
    if (dupes.length) throw new Error(`colliding resource names: ${dupes.join(', ')}`);

    // Fail before writing anything if a vendored source is missing.
    for (const s of inventory) {
        const spec = GLYPHS[s];
        if (spec.src.startsWith('iconoir:')) {
            const m = /^iconoir:(regular|solid):(.+)$/.exec(spec.src);
            if (!m) throw new Error(`${s}: bad src ${spec.src}`);
            const p = join(vendorDir, 'iconoir', m[1], `${m[2]}.svg`);
            if (!existsSync(p)) throw new Error(`${s}: vendored Iconoir art missing at iconoir/${m[1]}/${m[2]}.svg`);
        } else if (!HOUSE[camel(spec.src.slice(6))]) {
            throw new Error(`${s}: no house geometry for ${spec.src}`);
        }
    }

    mkdirSync(drawableDir, { recursive: true });
    mkdirSync(svgOutDir, { recursive: true });
    mkdirSync(testDir, { recursive: true });

    const manifest = [];
    for (const s of inventory) {
        const g = composeGlyph(s, GLYPHS[s]);
        const name = assetName(s);
        writeFileSync(join(drawableDir, `${name}.xml`), renderVector(s, g));
        writeFileSync(join(svgOutDir, `${name}.svg`), renderSvg(s, g));
        manifest.push({
            semantic: s, asset: name, entry: entryName(s), provenance: g.slug,
            source: GLYPHS[s].src, strokes: g.paths.length, weight: g.weight,
            note: GLYPHS[s].note ?? ''
        });
    }
    for (const style of DOCK) {
        writeFileSync(join(drawableDir, `ic_dock_${style.id}.xml`), renderDock(style));
        writeFileSync(join(svgOutDir, `ic_dock_${style.id}.svg`), renderDockSvg(style));
    }

    writeFileSync(join(componentsDir, 'VaultGlyph.kt'), buildKotlin(inventory));
    writeFileSync(join(testDir, 'ActionGlyphSetTest.kt'), buildGuardTest(inventory));
    writeFileSync(join(here, 'action-glyph-manifest.json'), JSON.stringify({
        generatedBy: 'build-actions.mjs',
        grid: { box: BOX, liveArea: LIVE, steadyWeight: W_STEADY, detailWeight: W_DETAIL, terminals: `${CAP}/${JOIN}`, detailPathThreshold: DETAIL_PATHS },
        glyphCount: manifest.length,
        dock: DOCK.map((d) => ({ id: d.id, label: d.label, w: d.w, h: d.h, blurb: d.blurb })),
        glyphs: manifest
    }, null, 2) + '\n');

    const derived = manifest.filter((m) => m.source.startsWith('iconoir')).length;
    console.log(`${manifest.length} action glyphs (${derived} derived+restyled, ${manifest.length - derived} house-drawn), ` +
        `${DOCK.length} dock silhouettes, VaultGlyph.kt, guard test, manifest`);
}

main().catch((err) => { console.error(err.message); process.exit(1); });
