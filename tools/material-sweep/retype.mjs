#!/usr/bin/env node
/**
 * Material ban sweep, pass 2 (plan: sync_engine/MATERIAL-SWEEP-PLAN.md, batch S1).
 *
 * Pass 1 put a house `Glyph` in every call site. This pass moves the *declarations* onto the same
 * type, so no component in the app still speaks in ImageVector: a parameter typed ImageVector is a
 * Material dependency even when the caller passes a glyph, and it would keep the banned artifact on
 * the compile classpath.
 *
 * Idempotent; --check reports without writing.
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '..');
const MAIN = path.join(ROOT, 'app', 'src', 'main', 'java');
const CHECK = process.argv.includes('--check');

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(p);
    else if (entry.name.endsWith('.kt')) yield p;
  }
}

const stats = { files: 0, params: 0, args: 0, calls: 0, imports: 0 };

for (const file of walk(MAIN)) {
  const original = fs.readFileSync(file, 'utf8');
  const eol = original.includes('\r\n') ? '\r\n' : '\n';
  // Stray carriage returns are stripped per line: a trailing \r makes an import line look like a
  // different line, and this script's job includes collapsing duplicated imports.
  let text = original
    .split(/\r+\n/)
    .map((line) => line.replace(/\r+$/, ''))
    .join('\n');

  // 1. every ImageVector-typed slot becomes a Glyph slot, spelled or fully qualified.
  //    Import lines are off-limits: an FQN in an import is an import, not a type position.
  text = text
    .split('\n')
    .map((line) => {
      if (line.startsWith('import ')) return line;
      let out = line;
      if (/androidx\.compose\.ui\.graphics\.vector\.ImageVector\b/.test(out)) {
        stats.params++;
        out = out.replace(/androidx\.compose\.ui\.graphics\.vector\.ImageVector\b/g, 'Glyph');
      }
      if (/: ImageVector\b/.test(out)) {
        stats.params++;
        out = out.replace(/:\s*ImageVector\b/g, ': Glyph');
      }
      return out;
    })
    .join('\n');

  // 2. the funnel parameter is `glyph`, whatever the value is
  text = text.replace(/\bimageVector\s*=\s/g, () => {
    stats.args++;
    return 'glyph = ';
  });

  // 3. material3 Icon() has no glyph slot; the app draws through ThemedIcon() only
  text = text
    .split('\n')
    .map((line) =>
      line.startsWith('import ')
        ? line
        : line.replace(/(?<![\w.])Icon\(/g, () => {
            stats.calls++;
            return 'ThemedIcon(';
          })
    )
    .join('\n');

  // 3b. the damage an earlier run of this script could do to an import line
  text = text
    .split('\n')
    .filter((line) => line !== 'import Glyph')
    .join('\n');

  // 4. imports: keep exactly what the file now refers to, and never twice
  const lines = text.split('\n');
  const tracked = [
    'import androidx.compose.ui.graphics.vector.ImageVector',
    'import androidx.compose.material3.Icon',
    'import com.securevault.pro.presentation.components.Glyph',
    'import com.securevault.pro.presentation.components.ThemedIcon'
  ];
  const needed = {
    'import androidx.compose.ui.graphics.vector.ImageVector': /\bImageVector\b/.test(text),
    'import androidx.compose.material3.Icon': /(?<![\w.])Icon\(/.test(text),
    'import com.securevault.pro.presentation.components.Glyph':
      !/^package com\.securevault\.pro\.presentation\.components$/m.test(text) && /\bGlyph\./.test(text),
    'import com.securevault.pro.presentation.components.ThemedIcon':
      !/^package com\.securevault\.pro\.presentation\.components$/m.test(text) && /\bThemedIcon\(/.test(text)
  };
  const kept = [];
  const present = new Set();
  for (const line of lines) {
    if (tracked.includes(line)) {
      if (needed[line] && !present.has(line)) {
        kept.push(line);
        present.add(line);
      } else {
        stats.imports++;
      }
      continue;
    }
    if (line.startsWith('import ') && kept.includes(line)) {
      stats.imports++;
      continue;
    }
    kept.push(line);
  }
  let insertAt = kept.reduce((acc, line, i) => (line.startsWith('import ') ? i : acc), -1);
  for (const line of tracked) {
    if (needed[line] && !present.has(line)) {
      kept.splice(insertAt + 1, 0, line);
      insertAt++;
      present.add(line);
      stats.imports++;
    }
  }
  text = kept.join(eol);

  if (text !== original.replace(/\r+\n/g, '\n')) {
    stats.files++;
    if (!CHECK) fs.writeFileSync(file, text.split('\n').join(eol));
  }
}

console.log(
  `${CHECK ? 'check' : 'pass 2'}: ${stats.files} files · ${stats.params} ImageVector slots retyped · ` +
    `${stats.args} call args renamed · ${stats.calls} Icon() calls routed through ThemedIcon() · ` +
    `${stats.imports} imports adjusted`
);
