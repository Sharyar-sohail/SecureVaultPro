# SVP-1 Realtime Sync Contract (binding for every agent)

Backend: Firebase project `vault-35423` ("SecureVault"), owner `sharyarsohail@gmail.com`.
- Realtime Database instance: `vault-35423-default-rtdb`, region **asia-southeast1 (Singapore)**
- URL: `https://vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app`
- Auth: Google provider already **Enabled**. Web OAuth client `908900914248-pse5k128rl0hoqb07oebhsnngt1i80uj.apps.googleusercontent.com`; Android client `908900914248-30uc7en3b1gcjto8e831kaj5jelrepeo.apps.googleusercontent.com`.
- Plan: Spark (free). No billing, no paid API. Never require a paid tier.

## Non-negotiables
1. **One backend, two frontends.** APK and Blogger theme read and write the *same* nodes under the *same* uid.
2. **Push, not poll.** No `setInterval` pull, no manual refresh, no "Sync now" as the mechanism. RTDB value listeners are the channel.
3. **Ciphertext only.** The database must never contain plaintext. Every record body is AES-256-GCM sealed under a per-user vault key.
4. **No universal key.** `PRIMARY_CLOUD_KEY` in `CloudSyncManager.kt` is dead for all new writes. It may only be read once, for migration. A test must assert it never appears in the Blogger theme or in any new write path.
5. **Never relax TLS.** `https://` / `wss://` only, no cert bypass, no cleartext.
6. **Biometric-only unlock** on device stays unchanged; sync must not introduce a device-credential prompt.
7. **Pre-login screens need a timeout and an escape.** A stalled socket must never dead-end the user.

## Node layout
```
svp1/users/$uid/
  keyring/{wrap, salt, alg, iters, updatedAt}      # vault key sealed by the user's passphrase
  data/{notes|entries|cards|totps|contacts|cryptoWallets|files}/$id = {
      e:  "<ivB64>.<ctB64>",   # AES-GCM(recordJson) under the vault key
      u:  <updatedAt ms>,      # last-write-wins clock
      r:  <rev int>,           # monotonic per user, from serverValue()
      d:  <deletedAt ms|null>  # tombstone: deletes propagate, never silently vanish
  }
  meta/{rev, updatedAt, lastDevice}
```
Media bytes stay in Google Drive (`DRIVE_FILE`) as today; only `{driveId,name,mime,size}` go in `files/$id`. The web fetches bytes on demand.

## Conflict rule
Per-record last-write-wins on `u`, ties broken by `r`. A record is only overwritten by a *newer* `u`; older writes are dropped and logged. Tombstones beat live records at equal `u`. This replaces the current whole-payload clobber that resurrects deleted items.

## Latency instrumentation (the acceptance metric)
Every client logs, per event: `tWrite` (ms, local, at call), `tAck` (ms, when the write is honoured locally), `tReceive` (ms, when the listener fires for a *remote* change). The delta that matters is `tReceive - tWrite` measured on the receiving side against the sender's `u`.
- Android: append to `Downloads/SecureVaultSync.txt` (same convention as `SecureVaultCrash.txt`).
- Web: `localStorage['svp-sync-log']` plus an on-page latency chip.
- Harness: `tools/sync-probe/` speaks the identical protocol from Node so the numbers are reproducible without a phone.

Target: median well under 1000 ms, and the number must be *measured*, never asserted.

## Definition of done for the program
`./gradlew testDebugUnitTest assembleRelease bundleRelease` green, signed with `release.jks` (R8 stays off), version bumped, plus a saved sync-test log with >=5 runs in both directions.
