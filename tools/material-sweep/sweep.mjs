#!/usr/bin/env node
/**
 * Material ban sweep (plan: sync_engine/MATERIAL-SWEEP-PLAN.md).
 *
 * Rewrites every `Icons.<family>.<Semantic>` reference in the app sources onto the house glyph
 * family (`Glyph.<entry>` from tools/brand-icons/action-glyph-manifest.json), retargets material3
 * `Icon(...)` calls onto `ThemedIcon(...)`, and drops the `androidx.compose.material.icons`
 * imports. Run with --check to report without writing.
 *
 * Deliberately literal: it only rewrites semantics the manifest knows about, and it exits non-zero
 * naming anything it could not map, so nothing silently falls back to a Material symbol.
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '..');
const APP = path.join(ROOT, 'app', 'src');
const MANIFEST = path.join(ROOT, 'tools', 'brand-icons', 'action-glyph-manifest.json');
const CHECK = process.argv.includes('--check');

const bySemantic = new Map(
  JSON.parse(fs.readFileSync(MANIFEST, 'utf8')).glyphs.map((g) => [g.semantic, g.entry])
);

/**
 * Material ships a semantic under more than one name; the frozen inventory keeps one.
 * QrCode2 is Material's newer spelling of QrCode — same mark, same glyph.
 */
const ALIASES = new Map([['QrCode2', 'QrCode']]);

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(p);
    else if (entry.name.endsWith('.kt')) yield p;
  }
}

const CALL = /(?<![\w.])(Icon)\s*\(/g;
const REF = /Icons\.(Rounded|Filled|Outlined|Sharp|AutoMirrored\.\w+)\.([A-Za-z0-9]+)/g;

/** Balanced-paren span of a call starting at the callee's opening bracket. */
function callEnd(text, open) {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

const report = { files: 0, refs: 0, iconCalls: 0, unmapped: new Set(), tintUnspecified: 0 };

for (const file of walk(APP)) {
  const original = fs.readFileSync(file, 'utf8');
  if (!/Icons\./.test(original) && !/androidx\.compose\.material\.icons/.test(original)) continue;

  let text = original;
  report.files++;

  // 1. symbols -> house glyphs
  text = text.replace(REF, (whole, family, semantic) => {
    const entry = bySemantic.get(ALIASES.get(semantic) ?? semantic);
    if (!entry) {
      report.unmapped.add(`${semantic} (${path.relative(ROOT, file)})`);
      return whole;
    }
    report.refs++;
    return `Glyph.${entry}`;
  });

  // 2. the funnel parameter is named `glyph` now
  text = text.replaceAll('imageVector = Glyph.', 'glyph = Glyph.');

  // 3. material3 Icon() has no glyph parameter: send those calls to ThemedIcon()
  {
    const hits = [];
    for (const m of text.matchAll(CALL)) {
      const open = m.index + m[0].length - 1;
      const close = callEnd(text, open);
      if (close < 0) continue;
      if (text.slice(open, close + 1).includes('glyph = Glyph.')) hits.push([m.index, m[0].length]);
    }
    for (const [start, length] of hits.reverse()) {
      text = text.slice(0, start) + 'ThemedIcon(' + text.slice(start + length);
      report.iconCalls++;
    }
  }

  // 4. imports
  text = text
    .split('\n')
    .filter((line) => !/^import androidx\.compose\.material\.icons\./.test(line))
    .join('\n');
  if (!/(?<![\w.])Icon\(/.test(text)) {
    text = text
      .split('\n')
      .filter((line) => line !== 'import androidx.compose.material3.Icon')
      .join('\n');
  }
  const inComponents = /^package com\.securevault\.pro\.presentation\.components$/m.test(text);
  if (/\bGlyph\./.test(text) && !inComponents && !/^import com\.securevault\.pro\.presentation\.components\.Glyph$/m.test(text)) {
    const lines = text.split('\n');
    const lastImport = lines.reduce((acc, line, i) => (line.startsWith('import ') ? i : acc), -1);
    if (lastImport < 0) throw new Error(`no import block in ${file}`);
    lines.splice(lastImport + 1, 0, 'import com.securevault.pro.presentation.components.Glyph');
    text = lines.join('\n');
  }

  report.tintUnspecified += (text.match(/tint = Color\.Unspecified/g) || []).length;

  if (text !== original && !CHECK) fs.writeFileSync(file, text);
}

console.log(
  `${CHECK ? 'check' : 'sweep'}: ${report.files} files, ${report.refs} symbol refs retargeted, ` +
    `${report.iconCalls} material3 Icon() calls moved to ThemedIcon()`
);
if (report.unmapped.size) {
  console.log('UNMAPPED SEMANTICS (left untouched, must be resolved):');
  for (const u of [...report.unmapped].sort()) console.log('  - ' + u);
  process.exit(1);
}
if (report.tintUnspecified) {
  console.log(`note: ${report.tintUnspecified} call(s) still pass tint = Color.Unspecified`);
}
const leftovers = [];
for (const file of walk(APP)) {
  const t = fs.readFileSync(file, 'utf8');
  if (/androidx\.compose\.material\.icons|\bIcons\./.test(t)) leftovers.push(path.relative(ROOT, file));
}
console.log(leftovers.length ? `material refs remain:\n  ` + leftovers.join('\n  ') : 'no material icon refs remain');
