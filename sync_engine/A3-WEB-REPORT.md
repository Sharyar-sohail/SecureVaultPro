# A3 — Web/Blogger client on the same realtime backend: build report

Owner: A3. Files touched (all inside my ownership, nothing else):

| File | Change |
|---|---|
| `tools/blogger-theme/secure_wallet_blogger_theme.xml` | Source-of-truth template. Realtime client added (3311 lines, one `//<![CDATA[` script block). |
| `app/src/main/assets/blogger_theme.xml` | Byte-identical copy of the template (what the APK ships and what the repo test pins). |
| `tools/blogger-theme/generate.js` | Reads the realtime wiring out of `google-services.json`; validates every URL and every injected id. |
| `tools/blogger-theme/generate.test.js` | Existing 7 tests untouched (the universal-key ban is still line 36); 3 new tests appended. |
| `tools/blogger-theme/README.md` | Documents realtime mode and the new flags. |
| `sync_engine/A3-WEB-REPORT.md` | This file (new). `sync_engine/HANDOFF.md` was **not** edited. |

Verification run: `node --test tools/blogger-theme/generate.test.js` → **10 tests, 10 pass, 0 fail**.
A separate scratch DOM harness (outside the repo,
`%TEMP%\svp-theme-smoke.js`, executes the theme's inline script in a fake DOM) → **24 checks, all pass**.
Generated theme parses as XML (`[xml]` load OK) and as JavaScript (`new vm.Script(...)` inside the test).

---

## 1. What was built

The published Blogger page is now a **second frontend on the same database**, not a viewer for a
dead snapshot. One theme carries three cooperating paths:

1. **Realtime (new)** — Google sign-in → Firebase session with the *same uid* the phone uses →
   keyring unwrap → live listeners on all 7 modules → decrypt → render → seal → write. Edits on the
   phone appear on the page and vice versa, no reload, no polling, no copy-paste.
2. **Sealed file (kept)** — pick a passphrase-sealed `.enc`/theme export, PBKDF2+AES-GCM in the
   browser, edit in-tab, re-seal.
3. **Drive (kept)** — GIS OAuth token → `appDataFolder` listing → open a sealed backup; save
   `secure_wallet_web.enc`.

The UI is still built at runtime by the script (Blogger drops widget-less body markup), so all new
surface is a `id='svp-live'` bar plus a latency/log/viewer panel in the same theme colours. Every
pre-login/loading state has a visible timeout and a **Try again** / **Sign out** escape, and
**Pause live** drops the key and detaches the listeners. No infinite spinner: each blocking step is
wrapped in `withTimeout(...)`, plus a 45 s busy watchdog that re-answers with the escape wording.

Firebase is loaded as **v10 modular ESM** from `https://www.gstatic.com/firebasejs/10.12.2/…`
(`firebase-app.js`, `firebase-auth.js`, `firebase-database.js`) through
`window.Function('u','return import(u);')`, so a browser without ES modules fails that one call with
a message pointing at the sealed-backup path instead of failing to parse the whole theme. Google's
button comes from `https://accounts.google.com/gsi/client`.

## 2. Exact paths read and written

`RT.root` is chosen by probe (see §4) and `rtPath(x) = RT.root + '/' + RT.uid + '/' + x`.

| Node | Path | Direction | Shape |
|---|---|---|---|
| Keyring | `svp1/users/$uid/keyring`, fallback `svp1/$uid/keyring` | read (`get`, 20 s timeout) | `{wrap, salt, alg, iters, updatedAt}` |
| Records | `svp1/users/$uid/data/<module>/$id` | `onChildAdded` / `onChildChanged` / `onChildRemoved`, and a transaction write | `{e,u,r,d}` — §1, exactly four keys or the node is refused |
| Module list | `svp1/users/$uid/data/<module>` | `get` on reconnect (`reconcile`, 25 s each) and `onChildAdded` for the live stream | children are the per-record nodes |
| Meta | `svp1/users/$uid/meta` | write, transaction | `{rev: serverValue(), updatedAt: Date.now(), lastDevice:'web'}` after every successful record write |
| Connection | `.info/connected` | `onValue` | drives the dot, the `reconnecting` chip, and the reconcile-on-reconnect |

`<module>` ∈ `notes, entries, cards, totps, contacts, cryptoWallets, files`. Record id = entity UUID,
used verbatim as the child key. Nothing is ever written outside `svp1/users/<own uid>/…`, and the uid
comes from the Firebase auth token, not from user input.

The mismatch between the frozen contract (`RECORD-FORMAT-v1.md` / `SPEC-v1.md` say `svp1/users/$uid`)
and the A1 recipe text (`HANDOFF.md:149` says `svp1/$uid`) is handled by probing both and then
**committing to whichever actually holds a keyring** for the whole session, so the client works
against either root without guessing per-call.

## 3. Per-module record JSON handling

`e` is `base64(12-byte IV) + '.' + base64(ciphertext‖128-bit tag)`; `openJson` splits, base64-decodes,
`crypto.subtle.decrypt({name:'AES-GCM', iv, tagLength:128}, RT.key, ct)` then `JSON.parse`. The parsed
object is passed through `normalizeRecord(module, json, id)`, which fills the §3 key table verbatim
(`RECORD_KEYS`, mirrored 1:1 with the contract) and coerces numbers (`digits`, `period`, `size`,
`expiryMonth`, `expiryYear`, `totalUsd`) and epoch-ms fields (`createdAt`, `updatedAt`, `lastAccessedAt`,
`expiresAt`, `uploadedAt`, `deletedAt`, `lastSyncedAt`) with a tolerant `toInt`.

The double-encoded columns are JSON **strings** and get a second parse inside `try/catch`, defaulting
to `"[]"`: `notes.tagsJson`, `entries.customFieldsJson`, `entries.tagsJson`, `cryptoWallets.addressesJson`,
`cryptoWallets.balancesJson` (`DOUBLE_ENCODED`). `parseJsonArray` also accepts an already-parsed array
and an object (wrapped in a one-item list) rather than throwing; `tagsToJsonString` re-emits a JSON
string for the write path, so a tag typed as `a, b` seals as `["a","b"]` and a stored `["keep"]` is not
double-stringified.

Decoding is per-record and sequential per module (`RT.chain[module]`), each step re-checks that the map
entry still holds the same node before adopting the result, so a fast remote change can't be overwritten
by a stale decode. A node that will not open renders as a visible **"Unreadable record"** placeholder
with the reason and increments `RT.decodeErrors`; it is never silently dropped, and it never renders
another user's data. A node whose shape is wrong (extra key, non-object, empty `e`) is refused before
decryption, counted in `RT.malformed`, and logged as `malformed` with the offending key names.

Conflict handling is §4 verbatim (`better(cand, cur)`): higher `u` wins → at equal `u` a tombstone
(`d != null`) beats a live node → otherwise higher `r` wins → anything losing is dropped **and logged**
with both `u`/`r`. `RT.dead[module][id]` keeps a guard so an older live node arriving later cannot
resurrect a deleted id, and `onChildRemoved` writes that guard too, so deletions never come back.

Writes: `sealJson(JSON.stringify(normalizeRecord(...)))` → `writeNode` sets `u = Date.now()`, marks
`RT.mine[module|id] = u` (the echo token), and runs
`transaction(ref, cur => { if (held && !better(node, nodeShape(cur))) return;  /* abort */ node.r = serverValue(); return node; })`.
`committed === false` throws a sentence naming the held `u`/`r`, releases the echo token, logs
`conflict-send`, and leaves the live map untouched — an old edit can never clobber a newer node.
Deletes write the same node shape with `d = u`. Files write metadata plus `driveId` only.

## 4. Keyring unwrap mechanics (no secret in the bundle)

`signInWithCredential(GoogleAuthProvider.credential(idToken))` from the GIS ID token gives the uid;
then `readKeyring(uid)` → `unwrapVaultKey(kr, passphrase)`:

1. `alg` (uppercased) must equal `AES-256-GCM` or the client refuses with a message naming the algorithm
   it was handed — it does not guess. Missing `wrap`/`salt` or a `wrap` without exactly one dot
   (`<iv>.<ciphertext‖tag>`) also refuse, each with "Nothing was unlocked".
2. `importKey('raw', utf8(passphrase), 'PBKDF2', false, ['deriveKey'])` →
   `deriveKey({name:'PBKDF2', salt: b64(kr.salt), iterations: coerceIters(kr.iters), hash:'SHA-256'},
   material, {name:'AES-GCM', length:256}, false, ['encrypt','decrypt'])`.
   `coerceIters` accepts a number or a string, defaults to **600000**, and falls back to the default for
   absurd values instead of deriving with 7 rounds.
3. `decrypt({name:'AES-GCM', iv, tagLength:128}, derived, ct)`. **Only this step's** rejection is
   reported as `wrong passphrase - nothing was unlocked.` — earlier failures keep their own wording, so a
   malformed node is never mislabelled as a wrong password, and a wrong password is never reported as an
   unlock of a vault that holds nothing.
4. The plaintext must be exactly 32 bytes or the key is rejected by length. It is then re-imported as a
   **non-extractable** AES-GCM `CryptoKey` (`extractable=false`), the byte buffer is `fill(0)`-ed, and the
   passphrase string is left as it was typed (never stored). `RT.key` is dropped by **Pause live**,
   **Sign out**, sign-out detected via `onAuthStateChanged`, uid change (`resetLiveData`) and lock.

The bundle contains no key material: only the project id, the RTDB URL, the web OAuth **client id** and
the public web **API key**, all read at build time from `app/google-services.json`
(project id from `project_info.project_id`, client id from the `client_type: 3` entry, API key from that
entry's sibling `api_key[].current_key`). `assertNotSecret()` rejects anything shaped like a client
secret or service-account key, and a key must match `^AIza[0-9A-Za-z_-]{20,60}$` (no `--`, no `goog`);
`assertPublicId()` does the same for client ids. `fbConfig()` will also take a key typed once into
**Google setup** and stored in `localStorage.svp.fbApiKey`, for a theme that was generated without one.
Every URL is `https:` — one test walks all `http(s)://` literals in the theme and only XML namespace
identifiers (`www.w3.org`, `www.google.com/2005/gml`) may stay `http`, because they are never fetched.

Known consequence of the non-extractable key: A1's recipe step 5 (open a Drive `{v:3,salt,data}` envelope
by using `base64(vaultKey)` as its passphrase, the Android `Keyring.sealingSecret()` equivalent) is not
possible on the web without importing the key as extractable. The Drive **restore fallback stays intact**
via the export-password path; a keyring-derived Drive open is a deliberate contract decision for the
Manager, not something I did silently.

## 5. Latency chip semantics (numbers are real)

Per incoming node the listener records `tReceive = Date.now()` and computes `delta = tReceive - node.u`.

* If `RT.mine[module|id] === node.u`, the change is **our own echo**: the delta goes to `RT.acks`
  (write-ack sample) and the echo token is consumed; it never enters the remote median.
* Otherwise it is **from another device**: the delta goes to `RT.latency` (last 60 samples) and is the
  number the chip shows.

So the chip reads `live <median(RT.latency)> ms`, the live bar reads
`recv <ms> ms · median <ms> ms · n <count> · write ack <median(RT.acks)> ms`, plus
`last <module>/<short id> · u <clock> · r <rev> · live|deleted · from another device|from this tab`.
`medianOf` is a true median of measured values (empty ⇒ shown as `-`, never 0). A negative delta is
flagged (clock skew) instead of hidden, and >1 s warns, >4 s turns red. Offline the chip says `offline`
(or `key loaded`), a lost socket says `reconnecting` — the chip never claims `live` without a sample.

Every sample and every notable event is appended to `localStorage['svp-sync-log']` (capped at 200 lines)
as `{at, evt, module, id, u, r, delta|tWrite|tAck|held_u|held_r, err}`. Events recorded: `recv`, `ack`,
`ack-delete`, `tombstone`, `send`, `send-delete`, `conflict`, `conflict-send`, `malformed`,
`decode-error`, `listen-error`, `removed`, `keyring`, `auth`, `live-on`, `paused`, `reconcile`,
`manual-reconcile`, `reconcile-error`. **Sync log** renders them newest-first via `logLine`, i.e.
`12:04:11.220 recv notes/abcdef12 Δ84ms u1730203451220 r12`, so a reviewer can confirm the medians
against the raw rows. Nothing in the UI asserts a number that was not measured.

Files never touch the database for bytes: `openViewer` shows name/type/size/`driveId` and fetches
`https://www.googleapis.com/drive/v3/files/<driveId>?alt=media` with the user's own Drive bearer token
(90 s timeout, 401 → "sign in again"), then renders image / PDF iframe / text through
`URL.createObjectURL(blob)`. The old 25 MB base64 embedding path is gone from the realtime flow — the
theme contains no `readAsDataURL` and no data-URL write.

## 6. Generator

```bash
cd "D:/sharyar/SMART VALUATE"
node tools/blogger-theme/generate.js \
  --out "C:/Users/Sharyar/AppData/Local/Temp/theme-check.xml"     # smoke copy
node tools/blogger-theme/generate.js                              # writes the template default out
node --test tools/blogger-theme/generate.test.js                  # 10/10
```

`generate.js` with no flags now prints the wired realtime summary (last run):

```
realtime: project vault-35423 · https://vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app
web client id: from google-services.json
firebase api key in theme: yes (public web key, from google-services.json)
size: 140.4 KB
```

It rewrites `var FB_CFG = {…}`, `var FB_VERSION = '…'` and `var DEFAULT_CLIENT_ID = '…'` in the template,
derives the RTDB URL (`databaseURL` from the JSON, else `https://<projectId>-default-rtdb.<region>.firebasedatabase.app`),
refuses any non-https URL, and warns (without failing) if `google-services.json` is missing so the theme
still builds for the sealed-file paths. `--google-services`, `--package`, `--firebase-api-key`,
`--rtdb-region`, `--database-url`, `--firebase-version` are documented in `tools/blogger-theme/README.md`.

## 7. What could not be verified without a live browser + phone pair

1. **The <1 s acceptance proof.** Latency is measured and the median path is unit-tested with a fake
   node (`recv 84 ms` on the chip, echoes excluded), but no real phone write was ever observed by a real
   browser. The number itself has to come from the paired run.
2. **GIS on blogspot.** One Tap / `renderButton` behaviour depends on the exact
   `https://securevaultpro.blogspot.com` origin being in the **Web** client's Authorised JavaScript
   origins, and on Firebase's Auth settings having a Google provider (the theme exchanges an ID token for
   a Firebase credential; if the Firebase Google provider is off, `signInWithCredential` fails). Repo
   content cannot show Google Cloud state.
3. **Database rules.** The client only ever reads/writes under its own uid, but whether
   `svp1/users/$uid` is readable and writable for `auth.uid === $uid` is a console setting; a rules miss
   surfaces as `listen-error` / permission-denied lines, not as a silent failure.
4. **The keyring node actually existing** for the test account — that is A1/A2's
   `Settings → Realtime vault key → Publish`, which the handoff still lists as untested on a device.
   Until it is published, the correct behaviour is the worded refusal this client prints.
5. **Which root A2 writes** (`svp1/users/$uid` per the contract vs `svp1/$uid` per `HANDOFF.md:149`).
   Probing covers both; the log line `keyring … root=<path>` records which one won, and A1/A2 should
   settle the contract on one spelling.
6. **Cross-open with the Android codec.** Same algorithm and same `iv.ciphertext‖tag` framing on paper,
   but no phone-decrypts-a-web-write / web-decrypts-a-phone-write round trip has been executed.
7. **Real Drive fetches** (`alt=media` previews, `appDataFolder` listing) and the visual/theme result in
   Blogger after the `Edit HTML → Save` publish step, including whether Blogger keeps the single CDATA
   script block intact — only the source file's well-formedness is verified here.
8. **Reconnect behaviour under a genuine socket drop** (`.info/connected` flip → reconcile): the state
   machine and the reconcile pass are harness-tested against fake refs, not against a real disconnect.
