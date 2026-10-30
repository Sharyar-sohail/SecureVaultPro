# Material icon sweep — manager plan (phase 2)

Measured on 2026-10-29 from the shipped 3.3.3 tree: **620 `Icons.*` call sites across 42 files**,
**107 distinct symbols** (frozen inventory: `sync_engine/material_glyph_inventory.txt`, per-file counts
in `sync_engine/material_sweep_inventory.tsv`).

The sweep can only start once the design agent's `ic_a_*` family and `VaultGlyph` accessor exist,
because every replacement references a drawable that must be on disk first. Batching is by whole file
so no two agents ever edit the same file.

## Naming contract
`Icons.Rounded.CheckCircle` -> `Glyph.CheckCircle` -> `ic_a_check_circle.xml`
(camel case preserved for the enum, snake case for the resource).

## Batches (run after phase 1 lands)
- **S1 — icon layer (the keystone, do first):** `presentation/components/ThemedIcons.kt` (101 sites).
  This file is the abstraction most screens already route through, so landing it correctly shrinks
  every later batch.
- **S2 — settings + backup + sync:** `SettingsScreen.kt` (63), `BackupScreen.kt` (20),
  `SyncCenterScreen.kt` (13).
- **S3 — vault + wallet + notes + contacts:** `EntryEditorScreen.kt` (20), `EntryDetailScreen.kt` (18),
  `WalletScreen.kt` (21), `NoteEditorScreen.kt` (12), `ContactsScreen.kt` (23).
- **S4 — scanners + files-adjacent + welcome/health/security:** `FileScannerScreen.kt` (14),
  `DocumentScannerScreen.kt` (13), `OcrScannerScreen.kt` (11), `VoiceRecorderScreen.kt` (14),
  `SetupScreen.kt` (12), `SecurityToolsScreen.kt` (12), `HealthScreen.kt` (12) and the remaining
  long tail.
- **S5 — owned elsewhere, swept last by their owners:** `HomeScreen.kt` (20, UI agent),
  `FilesScreen.kt` (47) + `WhatsAppChatHub.kt` (16) (WhatsApp agent),
  `CryptoWalletScreen.kt` (28) (crypto agent). These are only touched after those agents hand off,
  to avoid two writers in one file.

## Terminal gate (mine, before any build ships)
1. `grep -r "androidx.compose.material.icons" app/src/main/java` must be empty.
2. Delete `libs.androidx.material.icons.extended` from `app/build.gradle.kts` and
   `gradle/libs.versions.toml` — removing the dependency is what makes the ban structural rather
   than a convention.
3. A guard unit test fails the build if the import ever returns.
4. `compileReleaseKotlin` + full `testReleaseUnitTest` green, then the signed build.
