# A6 — WhatsApp chat import: verification and completion report

Owner: A6. Scope actually touched: `app/src/main/java/com/securevault/pro/data/imports/**`,
`app/src/main/java/com/securevault/pro/presentation/screens/files/**`,
`app/src/test/java/com/securevault/pro/data/imports/**`, plus **one line** outside it (stated below).

---

## 1. Verified as already correct (read, not redone)

| Claim | Where | Verdict |
|---|---|---|
| Content-based transcript discovery in the ZIP importer (richest `.txt` wins, loser sealed as an attachment, manifests skipped) | `WhatsAppImporter.kt:199–259` | **Correct.** Winner rule is `counted > messages`, so a tie keeps the first file and the second is sealed, never dropped. |
| Same probe in `WhatsAppArchiver.readZip` with a `transcriptHits` counter | `WhatsAppArchiver.kt:352–382` | **Correct.** Oversize `.txt` sets `transcriptTooLarge` and reports the 25 MB reason rather than "no transcript". |
| Manifest exclusion | `WhatsAppTranscript.isTranscriptManifest` / `MANIFEST_NAME` (`:69–73`) | **Correct** for `Missing.txt`, `_MISSING.txt`, `MEDIAS REMOVED.txt`, `medias_removed.txt`, `_WEBSITES_listing.txt`. |
| Wider stamp dialects | `WhatsAppTranscript.STAMP` (`:246–253`) | **Correct** — `15 Aug 2024`, `Aug 15, 2024` (+ meridiem), `2024-08-15`, `15/08/24`, bracketed `[15/08/2024, 10:23:45]`, and `at` separators all parse (now pinned by tests). |
| No silent busy gate | `FilesViewModel.kt:277, 392, 469` | **Partly landed.** `importFromUri`, `importWhatsApp`, `importFromWhatsAppHub` did set the message; **`importWhatsAppPicks` and `importPendingShare` still swallowed the pick** — fixed. |
| Picker durability | `FilesScreen.kt:745–767` (`keepReadable`, `DownloadsPicker`, `DownloadsManyPicker` with `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`), Downloads-first hint at `:755/:762` | **Correct** as landed. |
| Safety caps, PK sniffing, `.cryptNN` refusal | `WhatsAppImporter.kt:42–63, 85–104`, `WhatsAppArchiver.kt:320–330` | **Intact and untouched.** 25 MB text / 100 MB media / 600 MB total / 4 000 entries / 300 attachment metas / 250 MB export; archiver keeps its own 25 MB / 500 MB / 4 000. No cap was loosened. |

## 2. Real bug found in the count-parity chain (fixed)

`WhatsAppTranscript.transcriptOf()` returned *everything* after `— Conversation —`, and
`WhatsAppArchiver.chatBody()` writes the proof block **after** the transcript. The chat viewer
(`NoteEditorScreen.kt:101–103`) renders exactly `messages(transcriptOf(content))`, so the whole
`— Archive proof —` page was glued onto the **last message bubble**, and any bookkeeping line that
opens with a date (`2024-08-15, 10:25 - …`) was drawn as a message that never happened — i.e. the
drawn bubble count could exceed the count the archive summary reported.

Fix (`WhatsAppTranscript.kt`):
* added `PROOF_MARKER = "— Archive proof —"` and `transcriptOf` now cuts at it;
* `WhatsAppArchive.manifest` now writes `WhatsAppTranscript.PROOF_MARKER` instead of a literal, so
  the two can never drift apart again;
* deleted `WhatsAppImporter.countMessages()` + its `TIMESTAMP_BRACKET` / `TIMESTAMP_DASH` regexes —
  a *narrower* second counting dialect sitting next to the parser was a standing parity hazard
  (it was dead code; the importer already counted with the parser).

Parity now holds by construction and is asserted for all five fixtures in
`the reported count, the summary count and the drawn bubbles always agree` and
`an archived note draws exactly the messages the archive reported`
(`parse().messageCount == messageCount() == messages().size`, before and after a note round-trip).

## 3. Made the probe JVM-testable instead of device-testable

The probe lived only inside two Android classes that need a `Context`, so no unit test could reach
it. Moved the pure decision functions into `WhatsAppTranscript` (still pure JVM, no Android types)
and made both production loops call exactly those:

* `isTranscriptCandidate(name)` — name decides *eligibility* only (`.txt`, not a manifest).
* `messageCount(text)` — the ranking measure.
* `TranscriptCandidate(name, text)` + `bestTranscript(candidates)` — the richest wins; nothing with
  zero timestamps is a transcript at all.
* `titleFrom(name)` — moved out of `WhatsAppImporter` (was private, untestable) and **fixed**: the
  old code used `removeSuffix("_chat")` / `removeSuffix("_WAChatSource")`, which are
  case-sensitive, so iOS `Family _CHAT.txt` produced the title `_CHAT`. Now the chat tokens are
  stripped case-insensitively with a separator guard.

Used at `WhatsAppImporter.kt:226, 240, 247` and `WhatsAppArchiver.kt:370, 380`. Behaviour in the
streaming loops is unchanged (they still seal losers and index media as they go), only the
predicates are shared with the tests.

## 4. Navigate to the created note (done)

* `WhatsAppImportResult` gained `noteId` (`WhatsAppImporter.kt:23–31`) — `ChatArchive` already had it.
* `FilesViewModel` gained `openNote: StateFlow<String?>` + `noteOpened()` (`:167–173`).
* `importWhatsApp` success sets it immediately (the 2.4 s dead-time `delay` was removed — the note
  *is* the confirmation).
* `runWhatsAppBatch` opens the note when exactly one conversation was in the selection; several
  chats keep the user on Files where the list is the useful view. Same rule in `archiveFromHub`.
* `FilesScreen` gained `onOpenNote: (String) -> Unit = {}` (default keeps every other call site
  compiling) and a `LaunchedEffect` that navigates once (`:126, 131, 138–142`).
* **Cross-boundary edit (1 line, outside my ownership):** `presentation/navigation/VaultApp.kt:503`
  — `onOpenNote = { id -> navController.navigate(Routes.noteEditor(id)) }` in the `Routes.FILES`
  composable. No sibling listed that file as owned; it is additive and `FilesScreen` has a default
  for the parameter, so reverting the line still compiles.

## 5. Errors made visible (each path confirmed or added)

| Case | Message | Status |
|---|---|---|
| Oversize single media entry | `"$fileName is larger than the 100 MB media limit and was skipped."` | already there |
| Oversize text entry in a ZIP | `"$fileName was larger than the 25 MB text limit and was skipped."` | already there |
| Oversize plain `.txt` | `"That chat is larger than the 25 MB text limit. Export it without media…"` | **reworded** (was "larger than the supported limit") |
| Total media cap hit | `"Media stopped at the 600 MB export limit; the rest was left in the file."` | already there |
| Entry that will not seal | `"$fileName could not be sealed."` | already there |
| `.txt` with no timestamps (plain file) | `WhatsAppImporter.NO_TIMESTAMPS_HINT` | **added** — previously it silently saved a 0-message note |
| `.txt` with no timestamps (inside a ZIP) | same hint, kept as an attachment | landed, now shared constant |
| ZIP with no transcript at all | `"That .zip holds no chat transcript inside it — only its N media file(s) were kept…"` | **added** |
| Damaged / truncated ZIP | `"$displayName is a damaged archive (…) WhatsApp cut it short…"` / `"… stops part-way through"` | **added** (`WhatsAppImporter.kt:113–122`) |
| Unreadable where it is stored (IO) | `"That file could not be read where it is stored (…) save it to this phone first."` | **added** (`:123–127`) |
| Picker grant revoked (`SecurityException`) | `"Secure Wallet lost permission to read that file. Pick it again…"` | **added** (importer `:89–98`, archiver `:309–316`) |
| Hub path swallowed its own reasons | `WhatsAppArchiver.readSource` used `runCatching{…}.getOrNull()`, turning "transcript too large" / "no transcript inside" into the generic *"That export could not be opened as a WhatsApp chat."* | **fixed** — it now throws its specific `IllegalArgumentException`; only the genuine open failures become the generic line |
| More than one warning | `FilesViewModel` showed only `warnings.first()` | **fixed** — `summarize()` shows the first two verbatim plus `(+N more)` |
| Busy gate on multi-pick / shared chat | silent `return` | **fixed** — visible "A transfer is already running…" |

## 6. Tests added

`app/src/test/java/com/securevault/pro/data/imports/WhatsAppTranscriptProbeTest.kt` (16 tests, no
secrets — fixtures are invented chat text; no passphrase or key appears anywhere):

`every exported name is eligible as a transcript candidate` ·
`whatsapp's own manifests never compete for the transcript` ·
`the zip probe picks the richest conversation, not the luckiest name` ·
`an ios uppercase export wins when it is the only one carrying messages` ·
`an archive whose text files hold no timestamps has no transcript at all` ·
`the probe ignores the folder a transcript is nested in` ·
`the chat name comes out of every export filename dialect` ·
`the reported count, the summary count and the drawn bubbles always agree` ·
`an archived note draws exactly the messages the archive reported` ·
`the proof block cannot masquerade as messages after the conversation` ·
`a pasted transcript is a chat without the archive header` ·
`each stamp dialect splits into the day and clock the owner saw` ·
`whatsapp notices are marked and never given a speaker` ·
`wrapped lines belong to the message above and do not change the count` ·
`the attachment a message carries is listed on that message only` ·
`the same chat hashes to the same digest however whatsapp wraps its lines`

Five fixtures cover the required names — `Ali Raza _chat.txt` (Android), `Family _CHAT.txt`
(iOS uppercase + `Aug 15, 2024, 10:24:11 AM`), `business_chat-9234567890@c.us.txt` (bracketed),
`_WAChatSource.txt` (ISO `2024-08-15`), `9234567890@c.us _chat.txt` (`15/08/24`), plus
`Missing.txt` / `_MEDIAS REMOVED.txt` / `notes.txt` that must not win. The ZIP tests build a
**real** archive with `java.util.zip.ZipOutputStream` and read it back with `ZipInputStream`
through the production predicates, so the probe is proven on bytes, not on a mock.

## 7. Gradle — run twice, both blocked before my code could execute

`./gradlew testDebugUnitTest --offline --console=plain` (run 1, 20 s) — tail of real output:

```
> Task :app:kspDebugKotlin
e: [ksp] InjectProcessingStep was unable to process 'LocalWipeService(android.content.Context,
com.securevault.pro.data.repository.VaultRepository,com.securevault.pro.data.remote.google.CloudSyncManager,
com.securevault.pro.security.CryptoManager,com.securevault.pro.security.AppLockManager,
com.securevault.pro.security.SessionManager,com.securevault.pro.data.remote.firebase.Keyring,error.NonExistentClass)'
because 'error.NonExistentClass' could not be resolved.
    => element (CLASS): com.securevault.pro.security.LocalWipeService
    => type (ERROR parameter type): error.NonExistentClass
> Task :app:kspDebugKotlin FAILED
BUILD FAILED in 20s
18 actionable tasks: 7 executed, 11 up-to-date
```

Same command a second time (13 s): identical `:app:kspDebugKotlin FAILED`,
`18 actionable tasks: 1 executed, 17 up-to-date`.

**No test ran.** The blocker is `security/LocalWipeService`'s 8th constructor parameter resolving to
`error.NonExistentClass` — a class in `data/remote/firebase/**` that a sibling (A1 realtime / A5
security) is mid-edit on. Nothing in `data/imports/**` or `screens/files/**` is implicated; KSP fails
before `compileDebugKotlin`, so my sources were never even type-checked. Per the speed rule I stopped
at two runs and did not touch a sibling's file. The Manager's authoritative build should re-run
`:app:testDebugUnitTest --offline` after A1/A5 land.

One compile hazard I did catch and fix by hand: `warnings += WhatsAppTranscript.NO_TIMESTAMPS_HINT`
referenced the constant in the wrong object — `NO_TIMESTAMPS_HINT` lives in
`WhatsAppImporter.Companion`; corrected to the unqualified name.

## 8. Not verified on a real device

There is no emulator and no `adb` in this environment, so **none of the following has been executed
against real WhatsApp exports or a real UI** — they are code-level guarantees only:

* opening the picker against a live SAF provider, the Downloads-first `EXTRA_INITIAL_URI` hint, and
  whether a given OEM file manager honours `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`;
* that a `.zip` exported on a real handset (with `medias/` folders, `_WAChatSource.txt`,
  `WhatsApp Image … at 10.23.45.jpeg` names) actually seals its media and links it to the note id;
* the navigation jump itself (`FilesScreen → Routes.noteEditor(id)`), that the note editor opens the
  imported id and draws it in chat mode;
* progress-bar behaviour, the 250 MB / 600 MB caps against a real 400 MB video, and cloud-mirror
  waiting after an import;
* that the new "damaged archive" and "lost permission" strings render fully in the error banner (the
  banner is a single `Text` in a `Row` — long strings wrap, which is fine, but unmeasured);
* decoy-vault behaviour during an import.

## 9. Left deliberately

* Local-file import stays the only path in. No Takeout/Gmail page opening was added or re-added;
  no new dependency; `app/build.gradle.kts` untouched; R8 stays off.
* A `_MISSING.txt` inside a ZIP is *not* preserved by the hub-archive path (it is indexed as media
  and never matched) while the picker path does seal it as an attachment. Harmless asymmetry, one
  small file, not worth a cap change — flagged rather than fixed.
* `importText` now refuses a `.txt` that parses to zero messages. If a future WhatsApp dialect
  breaks `STAMP`, that becomes the visible complaint — which is the intended failure mode (a named
  reason beats an empty note).
