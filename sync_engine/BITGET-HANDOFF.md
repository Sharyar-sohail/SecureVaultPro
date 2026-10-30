# Crypto Violet (Bitget, read-only) — build + handoff note

Scope as briefed: integrate **one Bitget account, read-only** into the existing Crypto module.
Balances and asset lists for spot plus one USDT-margined futures account. No trading, no
withdrawal, no transfer, no signing anything but the GET request itself. The UI says so in plain
words. Credentials sealed with the app's own vault crypto; never synced, never backed up, never
logged, masked to the last four characters of the API key.

Status: **code complete, unit tests written, NOT run on a device and NOT built here** (four agents
share this checkout and `./gradlew` was off-limits — see "Left for the next agent"). Everything
pure is pinned by JVM tests; everything Android is thin glue. No live Bitget call was made, so no
claim is made that one works.

Files (all paths from the repo root):

| File | What it is |
| --- | --- |
| `app/src/main/java/com/securevault/pro/data/wallet/BitgetClient.kt` | signed read-only client (NEW) |
| `app/src/main/java/com/securevault/pro/data/wallet/BitgetCredentialVault.kt` | pure sealing/masking/snapshot codec (NEW) |
| `app/src/main/java/com/securevault/pro/data/wallet/BitgetCredentialStore.kt` | DataStore + `CryptoManager` glue (NEW) |
| `app/src/main/java/com/securevault/pro/data/wallet/BitgetRepository.kt` | connect / refresh / disconnect orchestration (NEW) |
| `app/src/main/java/com/securevault/pro/data/wallet/CryptoEndpoints.kt` | 51-71: authenticated section appended (host + two paths + `productType`) |
| `app/src/main/java/com/securevault/pro/data/wallet/CryptoBalanceService.kt` | 82-115: public `usdPrices(symbols)` reusing the existing price sources |
| `app/src/main/java/com/securevault/pro/domain/model/CryptoModels.kt` | 98-183: Bitget vocabulary (`BitgetSecretBundle`, `BitgetCredentialState`, `BitgetAsset`, `BitgetSnapshot`, `BitgetFailure(Kind)`, `BitgetRead`) |
| `app/src/main/java/com/securevault/pro/presentation/screens/crypto/CryptoVioletViewModel.kt` | panel view model (NEW) |
| `app/src/main/java/com/securevault/pro/presentation/screens/crypto/CryptoVioletPanel.kt` | the panel + the key sheet (NEW) |
| `app/src/main/java/com/securevault/pro/presentation/screens/crypto/CryptoWalletScreen.kt` | 256-260: one `item { CryptoVioletPanel() }`, guarded by `state.decoy` |
| `app/src/test/java/com/securevault/pro/crypto/BitgetSignatureTest.kt` | known-answer signing, parsing, error wording |
| `app/src/test/java/com/securevault/pro/crypto/BitgetCredentialIsNeverSyncedTest.kt` | the credential-exclusion guard |

Nothing else was touched. **No Room change**: no entity, DAO column, table, or version bump, so
`data/local/VaultDatabase.kt` is untouched and no migration is needed — the risk the brief flagged
(Home crashing on its first query after an upgrade install) does not exist for this feature.
`security/AppLockManager.kt` is untouched too: there is no "panel enabled" flag, the panel is
present whenever the app is in normal mode. `data/remote/realtime/**` is **untouched as well**, and
that is deliberate — see "Out of the cloud".

---

## 1. Endpoints and signing, and what the docs actually say

`https://www.bitget.com/api-doc` is now a client-rendered SPA for the **v3 UTA** API and returns no
readable text to a fetcher, so the v2 spec was read from Bitget's own server-rendered markdown
copies of the same pages, plus the maintained SDK `tiagosiebler/bitget-api` for the response shapes:

* `https://www.bitget.com/docs/classic/rest-api.md` — the v2 signature section.
* `https://www.bitget.com/docs/classic/uta-api-upgrade-guide.md` — the v2 → v3 endpoint map.

Implemented exactly two calls, `GET` only, and the method is hard-coded so no other verb can be
issued from this class:

```
GET /api/v2/spot/account/assets                  (optional params coin?, assetType? — none sent)
GET /api/v2/mix/account/accounts?productType=usdt-futures   (productType is required)
```

The upgrade guide names these as the balance pair, verbatim: *"Get balance | `GET
/api/v2/spot/account/assets` (spot) / `GET /api/v2/mix/account/accounts` (futures) →
`GET /api/v3/account/assets` (single endpoint for all products)"* — and it settles the casing of the
one parameter this client sends: *"`productType` (e.g. `usdt-futures`) | `category`
(e.g. `USDT-FUTURES`) | Same concept, renamed and uppercased in v3"*. So **v2 is lower case**, which
is what `CryptoEndpoints.BITGET_MIX_PRODUCT_TYPE` holds; the v3 spelling would be rejected.

Signature, verbatim from `rest-api.md` §Signature, and implemented at `BitgetClient.kt:103`
(`preHash`) and `:115` (`sign`):

> The request header of ACCESS-SIGN is to encrypt **timestamp + method.toUpperCase() + requestPath +
> "?" + queryString + body** string (+ means string concat) by **HMAC SHA256** with **secretKey**, and
> encode the encrypted result through **BASE64**.
> **timestamp**: same as the ACCESS-TIMESTAMP header, milliseconds since Epoch.
> **queryString**: if the queryString is empty, signature content is
> `timestamp + method.toUpperCase() + requestPath + body`.
> **body**: if the request body is empty (usually a GET request), the body can be omitted.
> **Timestamp**: the timestamp of the request must be within 30 seconds of the API server time,
> otherwise the request will be considered expired and rejected.

So: the `?` is inserted only when there is a query, the empty body contributes nothing, the same
query string is signed and sent (`encodeQuery` output feeds both), and `ACCESS-TIMESTAMP` and the
signed timestamp are the identical string. Headers sent: `ACCESS-KEY`, `ACCESS-SIGN`,
`ACCESS-TIMESTAMP`, `ACCESS-PASSPHRASE`, plus `Content-Type: application/json`, `Accept`,
`User-Agent: SecureWalletPro/3.0`, `locale: en-US`.

**The base64 question, answered in the open.** The brief said to use the secret as a base64-decoded
key *if it decodes cleanly*, otherwise raw UTF-8. The shipped code does the opposite of the
conditional: it **always uses the raw UTF-8 bytes of the secret string**, because that is what
Bitget's own samples in the same document do — Java
`new SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA256")`, Python
`HMAC.new(bytes(secret_key, encoding='utf8'), ...)` — while Bitget secrets *happen* to be
base64-shaped. A "decode it if it looks like base64" rule would therefore pick the wrong key for
most real secrets and answer 40003. Both variants are pinned in `BitgetSignatureTest` so the choice
is a fact in the test suite rather than a coin flip: raw UTF-8 over the spot pre-hash is
`hrxVnDJES3n2S6RmJNVmMZrVs/ifpKcFoo3GXo4YXSQ=`, the base64-decoded key over the same input is
`9SHQA7ma+4XZJ8ImF4xcqYrpfqm3SgpQc9NIgO/Rr3o=`, and the test asserts they differ.

Known answers used (all fabricated inputs — the secret is `base64("fake-secret-value-for-test-only")`,
the timestamp is `1700000000000`). They were derived with `node:crypto`, not by the Kotlin code, and
then re-checked against `javax.crypto.Mac` on a desktop JVM:

| pre-hash | signature |
| --- | --- |
| `1700000000000GET/api/v2/spot/account/assets` | `hrxVnDJES3n2S6RmJNVmMZrVs/ifpKcFoo3GXo4YXSQ=` |
| `1700000000000GET/api/v2/mix/account/accounts?productType=usdt-futures` | `Tc0/oEiL6/JAkPM8agTBgLR66mrWa1Rf10s9dKBotDI=` |
| `1700000000000POST/api/v2/mix/order/place-order{"productType":"usdt-futures","size":"1"}` | `VvPUy5eibxuxYSKUtwN/9CipbU0KLvAsadDo14E7sEc=` |

The third row is a body/ordering probe only — this app has no POST path, it exists to prove `body`
concatenates last.

Transport rules (`BitgetClient.kt:342` `get`): `java.net.HttpURLConnection` over **HTTPS only**, a
closed host allow-list taken from `CryptoEndpoints.bitgetApi` (`https://api.bitget.com`) — a
mismatched host is refused before the socket opens; 8 s connect / 12 s read timeouts;
`instanceFollowRedirects = false`, because a redirect would replay `ACCESS-KEY` to whichever host
the server names (a 3xx is reported as "something tried to redirect the request… turn off any VPN"
with the fix, not the code); response capped at 512 KiB by a bounded reader that throws a
data-free `Oversized` (`:415`) so a runaway body cannot exhaust the heap and the exception cannot
carry body text; **no retry at all** — a 429 or a 40009 clock-skew rejection is turned into words
for the owner instead of hammered until the key gets locked.

Failure mapping (`failureOf`, `:196`) covers `40001` invalid key, `40003` signature, `40006`
passphrase, `40009` timestamp expired, `429` rate limit, `403` permission denied, plus HTTP-level
fallbacks and a "answered, but not in the documented format" branch for an unparseable body (empty
code, so the owner never sees a made-up number). Every message is a literal from a table; the
server's `msg` is never echoed, so no header value and no key fragment can ride out through an
error. `BitgetSignatureTest.noFailureMessageEverCarriesCredentialMaterial` asserts that against the
fake triple.

Prices come from the app's existing keyless sources, not a new feed: `CryptoBalanceService.usdPrices`
(:88) checks the CoinGecko map the balance scan already uses (BTC/ETH/BNB/POL/TRX/USDT/USDC) and
falls back to Binance USDT pairs for whatever is left; a symbol nothing answers for is simply absent
and the row shows the amount with no USD value.

## 2. Where the credentials live

`BitgetCredentialStore` (`:35`) owns **its own DataStore file**, `svp_bitget_credentials`, next to
`AppLockManager`'s `vault_security_prefs` in style, but separate from everything else. Fields
(`BitgetCredentialFields`, vault `:28-53`):

```
label            plain  "Main account"
maskedApiKey     plain  "********6789"  — eight dots + last four, nothing else ever renders
addedAt          plain  millis
sealedApiKey     AES-256-GCM envelope of the API key
sealedApiSecret  AES-256-GCM envelope of the secret
sealedPassphrase AES-256-GCM envelope of the passphrase
lastSnapshot     the last read, as a plain text document of numbers and symbols
lastSnapshotAt   millis
```

The three secret values are sealed **individually**, each through the same call the rest of the app
uses for a seed phrase: `CryptoManager.encrypt` (`security/CryptoManager.kt:71`), i.e.
`AES/GCM/NoPadding`, a fresh 12-byte IV per call, 128-bit tag, envelope
`base64(iv).base64(ciphertext+tag)`, under the Keystore-wrapped, non-exportable device data key.
`KeyDerivation` (PBKDF2-HMAC-SHA256, 600 000 iterations, 32-byte salt, 256-bit key) is what that key
management already does for `WalletRepository` — nothing was re-implemented, no new crypto was
invented, and the **deprecated universal cloud key is nowhere near this**. `BitgetSecretSealer`
(vault `:22`) is the one-method seam so the vault core is testable off-device; on the device it is an
anonymous wrapper around `CryptoManager` whose `seal` returns null on a blank input or a thrown
error.

Two properties the code is written to keep:

* **No plaintext fallback.** `fieldsFor` (vault `:119`) seals all three; if any seal returns blank
  it returns `SealedResult.failure(...)` and **no fields at all**, so a broken Keystore cannot leave
  half a credential on disk.
* **Rendering never decrypts.** `stateOf` (vault `:145`) reads only `label`/`maskedApiKey`/`addedAt`
  and the presence flags; the test asserts the sealer's open counter stays at 0 while the panel
  state is produced. Decryption happens exactly three times per refresh, inside `secretsOf`
  (vault `:160`), which requires all three fields or returns null. The plaintext triple then goes
  straight into the two signed GETs and is dropped: `BitgetUiState` has no field able to hold it,
  and `BitgetRepository` never returns it.

"Remove credentials" is `BitgetCredentialStore.clear()` → `edit { it.clear() }`, awaited by the view
model, which then re-reads the state and only claims success if the record really is gone
(`CryptoVioletViewModel.disconnect()`). The dialog says plainly that the key stays alive on Bitget
until the owner deletes it there, because this app cannot delete it.

## 3. Out of the cloud — how, and how it is proven

The realtime sync payload is built in exactly three steps, and Bitget takes part in none of them:

1. `RealtimeStore.loadAll(module)` pulls rows from **one Room DAO** (or, for `files`, the file
   vault). The mapping is an exhaustive `when` over `RealtimeModule` — seven segments, no default
   branch, no reflection over "whatever is in storage".
2. `RealtimeEngine.pushOne` calls `RealtimeRecordCodec.encode(module, record)` and
   `RealtimeEnvelope.seal` encrypts that string into the `e` field.
3. Reading back, `RealtimeFieldSchema.identify`/`accepts` refuse any body whose key set is not
   exactly one of the seven published shapes — an unknown key disqualifies every module rather than
   being ignored.

Bitget credentials are in **DataStore**, which no DAO and no module reads, so they never enter step
1. Their record types are in no module's class table and their field names are in no published key
table, so steps 2 and 3 could not carry them even if something handed them over. That is why
**zero edits were made under `data/remote/realtime/`**: the exclusion is structural, and the
alternative — a deny-list inside the codec — would have needed a change to a hot path
(`RealtimeEngine.kt:404` calls `codec.signature(module, record ?: Any())`) for no added guarantee.

`BitgetCredentialIsNeverSyncedTest` is the machine-checkable form of the claim:

* for the field map, the credential state, the plaintext bundle, an asset and a snapshot: every
  module reports `holds() == false`, `moduleOf() == null`, `recordOf() == null`, and
  `encodeRecord()` throws — no module will serialize any Bitget object;
* no published key table contains a credential-shaped key (`apiKey`, `apiSecret`, `passphrase`,
  `access-key`, `access-sign`, `sealed*`), and the `cryptoWallets` table is asserted **unchanged**,
  in order, with the ten names it has always had;
* the segment list is still the seven in the frozen order, and `bitget` / `violet` / `apiKeys` /
  `exchangeKeys` resolve to nothing;
* three credential-shaped JSON bodies (a bare key triple, a sealed-field record, and the wallet
  shape with an extra `apiKey` bolted on) decode to null through `decodeAny`, `identify` and
  `decodeRecord` for every module;
* stored fields contain no plaintext of the fake triple and the mask leaks at most four characters;
* removal deletes every field name the feature can write, so nothing survives under a name
  `clear()` never looks for.

Balances are a different story and stay in sync with the rest of the app's philosophy: the snapshot
is numbers and symbols, and it is written into a document that `carriesSecret()` (`BitgetCredentials
`, vault `:102`) refuses if it ever names a credential field. Today the snapshot is *not* pushed
either, because it is not a Room row — see "Non-goals". Nothing Bitget is in a Firebase DTO, a
Google Drive backup, or the Blogger payload: no export/backup file was touched.

Logging: none of the new files has a log call at any level. Every failure path returns a data object;
`catch` blocks are silent by design (a DataStore write failure is reported by the caller's read-back
rather than by a message that could carry record content).

## 4. UI

`CryptoVioletPanel` renders inside the Crypto screen as one card, in the existing
`GlassCard`/`ThemedIconTile`/`TagChip`/`GhostButton`/`GradientButton`/`ConfirmDialog` idiom, and the
key sheet is a `ModalBottomSheet` following the wallet-import sheet (`CryptoWalletScreen.kt:1166`
`AddWalletSheet`, whose three-field block is 1215-1235) rather than a new dialog system. Only
`VaultColor` semantic roles
(`presentation/theme/Role.kt`) and `VaultShapes` tokens are used — no hex literals, no new palette —
so the Royal Violet theme being added by another agent picks this panel up automatically; the accent
is `VaultColor.decorative`, which is already that purple.

Always visible, in the panel itself: *"Read-only. Secure Vault Pro can see your balances and asset
names, and nothing else: it cannot place an order, move funds, withdraw, or sign anything with your
key. The three credential values are sealed with your vault key on this phone and are never synced
to the cloud."* The sheet repeats the operational instruction: *"On Bitget, open API › Create API key
and set Permissions to 'Read only'. Leave Enable Trading, Enable Withdrawals and Transfers OFF."*
plus a row that opens `https://www.bitget.com/account/api` in the browser.

Also: a `READ ONLY` chip, the label and masked key (`Main account · key ********6789`), the total in
USD, last-synced time, manual **Refresh** only (no background timer), asset rows (top 8, then
"N more assets") with spot/futures subtotals, "Reading…" states, a failure card whose headline names
the problem ("Phone clock is off", "Signature rejected") and whose body names the fix, and
**Remove key** behind a confirmation dialog. Demonstration (decoy) mode renders nothing and offers no
way to add a key — the panel returns early and the screen skips the item entirely.

## 5. Deliberate non-goals (for the record)

* **Bitget balances do not join the Room portfolio total, the send flow, or the asset list.** They
  have no `CryptoNetwork` id, so `fundedAssets()` yields nothing for them; making them countable
  would mean adding a row to a synced table, which is exactly the credential-adjacent risk this
  design avoids. The panel shows its own total.
* **The snapshot is not mirrored to the cloud.** The brief permits balances to sync like wallets do,
  but the only way to do that is a `crypto_wallets` row — a synced table whose every column is
  uploaded. Rather than a new table (Room version bump + migration + upgrade-install risk), the last
  read stays in the same local file as the sealed credentials. If the owner later wants Bitget
  balances on the Blogger page, the correct move is a *new synced segment* for numbers only, not a
  column next to a ciphertext.
* No trading endpoints, no withdrawal, no transfer, no signing of payloads, no WebSocket, no
  sub-account, no v3, no other exchange.
* No auto-refresh, no push, no notification, no widget, no background worker.

## 6. Left for the next agent (i.e. what is genuinely untested)

Nothing in this list is a known defect; each is a claim this machine could not check. There is no
emulator, no adb, no device, and no Bitget account or API key here, and `./gradlew` was not run
because four agents share the checkout.

1. **Build.** `./gradlew testDebugUnitTest assembleDebug`. Compile risks I could see were removed by
   hand (no new dependency; `const` mirrors of internal constants became plain `val`s so a public API
   never reads an internal one at compile time; `editSafely` takes a `suspend` lambda so the
   DataStore `edit` coercion is explicit; `ThemedIcon`/`GlassCard`/`GhostButton`/`GradientButton`/
   `VaultTextField`/`ConfirmDialog`/`TagChip` parameter names were each re-read from
   `presentation/components` before use). Expect the tests to be the honest signal.
2. **The unit tests themselves.** They are pure JVM and should run anywhere; their expectations were
   computed independently (`node:crypto` + a standalone `javac`/`java` HMAC harness) — but they have
   not been executed by a Gradle run in this checkout.
3. **A real signed call.** Untested end to end: whether Bitget's answer for `/api/v2/mix/account/accounts`
   on the owner's account is a per-coin array as assumed (parser reads `marginCoin`, `accountEquity`,
   `isolatedMargin`, `crossedMargin`), whether a spot account with holdings returns `frozen`/`locked`
   strings as assumed (`coin`, `available`, `frozen`, `locked`), and whether the v2
   `productType=usdt-futures` casing is accepted without a `symbol` param. First live run will tell
   in one refresh; the panel shows the code-free explanation if it does not.
4. **Android Keystore + DataStore on ColorOS.** Whether `CryptoManager.encrypt` behaves under the
   OPPO keymaster for three short strings, whether the DataStore file survives an app-force-kill,
   and whether `clear()` really lands before the panel re-reads. The store is defensive (blank/failed
   seal → nothing stored; read failure → empty map), but not proven on the handset.
5. **Error-code wording.** `40001`/`40003`/`40006`/`40009`/`429` were taken from the brief plus the
   docs' error table; the *sentences* are mine (each names the fix). If Bitget's live numbers differ
   for the same failure, the mapping falls through to the generic branch — visible as "Bitget refused
   the request (code …)", not a crash.
6. **The Bitget key-page deep link.** `https://www.bitget.com/account/api` is the conventional path,
   not verified from a logged-in session; if Bitget moves it the row still opens the site root.
7. **No server-time check.** There is no verified v2 public time endpoint in use, so a clock-skew
   failure is reported rather than corrected. The fix text tells the owner to turn on automatic date
   and time. If the next agent wants to be kind about it, `GET /api/v2/common/timestamp` is the
   obvious candidate — **unverified here, do not ship it on the strength of this note.**
8. **UI on a real screen.** The sheet's four fields on a 6.4-inch OPPO with the soft keyboard up
   (`imePadding` + `navigationBarsPadding` are in), and whether eight characters of dot-leader read
   well next to a masked key.

## 7. Places I had to guess

* The v2 paths themselves are documented and cross-checked; **the `productType` casing** is the one
  place where the v2 docs and the SDK's v3-style type disagree, resolved in favour of the docs'
  literal `usdt-futures` (quote in §1) and asserted in a test so a future edit cannot drift.
* **`mix/account/accounts` needs no `symbol`** for an account-wide read — inferred from the docs'
  parameter table (only `productType` required) and the SDK's `getAccountList` call shape. If Bitget
  changes that, this leg fails alone and the spot balances still render, with the futures row
  explained in the notice line.
* **Futures "available" is `accountEquity`, "on hold" is `isolatedMargin + crossedMargin`** — the
  closest documented fields to what a balances card means. Labelled as such in the UI ("Futures
  equity"), so the owner is not misled into reading it as withdrawable.
* **`429` handling**: the brief asked for "a `429`/`40009` timestamp-skew path". Treated as two
  distinct outcomes — HTTP 429 → rate-limit sentence, 40009 → clock sentence — with **no retry in
  either case**, which is the conservative reading of "report it in words the owner can act on rather
  than retrying forever".
* **Masking length**: eight dots plus four characters. The brief said "last 4 of the key only"; the
  dots are cosmetic and are asserted in a test to be the whole of the visible prefix.
* `BitgetFailure` carries the raw code as an optional field that the UI never prints (only headings
  and sentences are rendered). Keeping it was a judgement call for support conversations; it cannot
  hold credential material because it is built from a fixed table.
