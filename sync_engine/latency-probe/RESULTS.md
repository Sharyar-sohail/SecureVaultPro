# SVP-1 Sync Measurement — Results (2026-10-30)

Scope: prove the transport is push (not polling), MEASURE the network path from this
Windows machine to the production RTDB host, and state honestly what remains unmeasured.
Probe: `node sync_engine/latency-probe/probe.js` (Node built-ins only, secrets-free).
Raw samples: `raw-2026-10-30.json` (this directory). No application source was modified.

Target host: `vault-35423-default-rtdb.asia-southeast1.firebasedatabase.app:443`
(the URL `FirebaseBootstrap.kt:28` and the theme `blogger_theme.xml:138` both use).

## 1. Measured transport numbers (this machine)

Run: 2026-10-29T19:15:04Z, node v24.18.0, samples=15 (12 for WS, 5 for SSE). Anonymous:
the per-uid rules reject data requests, but TLS/WS handshake and edge-rejection latency is
served by the same edge that serves realtime traffic — these are the road, measured.

| phase                                        | n  | min     | median  | p95     | max      |
|----------------------------------------------|----|---------|---------|---------|----------|
| DNS lookup (OS cache, `dns.lookup`)          | 15 | 0.35 ms | 0.40 ms | 1.84 ms | 151.05 ms (one cold-cache sample) |
| DNS resolve4 (network, UDP 53)               | 0  | —       | —       | —       | —  NOT MEASURABLE here: recursive UDP resolver returned nothing on this network; OS cache path covers resolution |
| TCP connect (SYN→SYN/ACK, port 443)          | 15 | 118.84 ms | 121.70 ms | 123.17 ms | 125.62 ms |
| TCP+TLS full connect (TLS 1.3)               | 15 | 580.80 ms | 587.93 ms | 665.67 ms | 1030.20 ms |
| → TLS handshake alone (total minus TCP)      | 15 | ≈462 ms | ≈466 ms | ≈544 ms | ≈905 ms  |
| WS upgrade request → 101 response            | 12 | 453.09 ms | 457.03 ms | 461.18 ms | 462.97 ms |
| WS upgrade → first server frame              | 12 | 453.93 ms | 457.40 ms | 462.05 ms | 463.31 ms |
| HTTP event-stream TTFB (SDK fallback path)   |  5 | 452.23 ms | 455.46 ms | 457.21 ms | 458.00 ms |

Facts measured alongside:
- **WebSocket upgrade accepted**: 12/12 `101 Switching Protocols` on both `/.ws` and
  `/svp1/.ws`; `Sec-WebSocket-Accept` recomputed from `SHA1(key + RFC6455 GUID)` matched
  **all 12**. The hand-built masked client PING/close frames were accepted (codec proven).
- First server frame arrived ~0.4–4 ms after the 101 (median 457.4 vs 457.03 ms): the edge
  **pushes a frame down the fresh socket unprompted** — content was the legacy-protocol
  version notice `{"t":"c","d":{"t":"s","d":"Version error…"}}`, i.e. a live Firebase
  handshake stream, not an HTTP reply.
- TLS: TLSv1.3 on all 15, cipher `TLS_AES_256_GCM_SHA384`. **Session resumption was NOT
  reused** (session ticket passed to a fresh `tls.connect`; server did not reuse, 617.85 ms).
  Every new connection pays the full TLS cost — relevant to reconnect time, not to steady-state pushes.
- Consistency: yesterday's HTTP floor log (`sync_engine/logs/transport-floor-2026-10-29T11-13-02-247Z.txt`)
  measured round trips 468/481/1145 ms (min/median/p95) — the WS 457 ms and SSE 455 ms medians
  agree with it. The one-way region RTT is ~120 ms TCP; a full request/response edge trip is ~455–480 ms.

**What these numbers do and do not mean.** They bound the cost of *establishing* transport and of a
request/response trip to the Singapore edge from this PC. An established push does NOT pay them again:
once the socket is open (as it is on both clients), a server→client push frame rides the existing
connection; the observed unprompted-frame delay after upgrade (median 457.40 − 457.03 = **~0.4 ms**)
shows the server emits downstream frames immediately on an open socket. The end-to-end write→receive
figure, however, remains NOT MEASURED (see §3).

## 2. Transport evidence — push, both clients (file:line)

Android (`app/src/main/java/com/securevault/pro/`):
- Firebase init (lazy, guarded): `data/remote/firebase/FirebaseBootstrap.kt:59` (`FirebaseApp.initializeApp`),
  `:84` (`FirebaseDatabase.getInstance(app, url)`), URL template `:28` — same asia-southeast1 host.
- Persistent listeners: `data/remote/realtime/RealtimeEngine.kt:264-281` — one `ChildEventListener`
  per module under `svp1/users/<uid>/data/<module>`; callbacks `onChildAdded/Changed/Removed/Moved`
  at `:267-270`, registered at `:279` (`addChildEventListener`). The Firebase RTDB SDK keeps ONE
  persistent websocket (webchannel) multiplexed connection underneath; `onChildAdded` is a stream
  event, not a re-fetch.
- Listener lifecycle: `removeEventListener` at `RealtimeEngine.kt:503-512` (`detachListeners`, called
  from `quiet()` :248-253 and re-attach :263).
- Write path: `RealtimeEngine.kt:362-411` — `ref.setValue(envelope).await()` (:397) fired straight
  from a channel (`pushWake`, :111/:166/:173); no reload anywhere in Kotlin (a Compose app has none);
  latency noted on both sides (:308, :406). Offline queue: `setPersistenceEnabled(true)` at :153.
- No time-based refresh: the realtime package has **zero** `delay(...)`/`Timer`/poll loops
  (the only `while (true)` in `RealtimeEngine.kt:524` is `drain()` of a channel).
  `AutoSyncManager.kt` triggers are event-driven: `vaultRepository.mutations.collect {
  realtimeEngine.scheduleReconcile() }` (:91-94) and `markDirty()` (:110-115). The Drive path's
  `delay(DEBOUNCE_MS)` (:98) is the separate 500 ms archive debounce — RealtimeEngine explicitly
  does not wait on it (contract comment `RealtimeEngine.kt:63-65`). Drive retry loop is bounded
  (5 attempts, `AutoSyncManager.kt:120-128`).

Web clone (`tools/blogger-theme/secure_wallet_blogger_theme.xml`, mirrored byte-identically into
`app/src/main/assets/blogger_theme.xml` — verified this session: sha256 `68c05d19…` both):
- SDK: modular `firebase-database.js` loaded from gstatic (`:1526-1531`) → websocket/webchannel
  transport in the browser.
- Same listener shape: `onChildAdded/onChildChanged/onChildRemoved` at `:2018-2023` inside
  `attachListeners()` (`:2011`), one-time bulk `.get()` at go-live only (`:2308` → `reconcile()`
  `:2054`), unsubscription collected at `:2024-2025` and run in `detachListeners()` (`:2042`).
- Reconnect handling: `.info/connected` listener `:2028-2038`; on a false→true transition it
  re-runs `reconcile()` (`:2036`) — recovery sweep, not a timer.
- No polling: the **only** `setInterval` in the theme is `:862`, refreshing TOTP *display codes*
  every second locally (`refreshTotp` `:853-860`, WebCrypto computation, zero network). `reconcile()`
  is called from exactly three places: go-live (`:2308`), reconnect edge (`:2036`), and the manual
  "Reconcile" button (`:2890-2892`). Nothing time-based re-reads data.
- Write path: `writeNode` (`:2222`) seals and runs an RTDB `transaction` with a 25 s bound; the ack
  handler updates in-memory maps and `scheduleRender()` (debounced 120 ms re-render of local state,
  `:2109-2116`) — no `location.reload` exists in the file.

## 3. Instrumented end-to-end latency — where the numbers live

Android device:
- `SyncLatencyLog.kt:65-95` appends every event to **`Downloads/SecureVaultSync.txt`** (MediaStore,
  openable in the Files app; names at `:158-159`): `PUSH … tWrite=… tAck=… ack-lag=…ms` and
  `RECV … u=… tReceive=… delta=…ms` (delta = receive minus sender clock, negatives kept —
  clock-skew is reported, not hidden, `:79-82`).
- Settings screen live readout: **PUSH LATENCY / PENDING / LISTENERS** tiles,
  `presentation/screens/settings/SettingsScreen.kt:809-825`, fed from
  `RealtimeEngine.status.lastLatencyMs` (`RealtimeEngine.kt:309-311`, `SettingsViewModel.kt:217-223`).

Web clone:
- In-page **Sync log** button (`:259`, handler `:2894-2900`) reveals `#svp-log`: the last 25 lines of
  the capped 200-entry `localStorage['svp-sync-log']` (`logSync` `:2178-2186`), carrying
  `recv Δ…ms`, `ack rt…ms` (write→own-echo round trip), `send tAck`, conflicts, and reconnect marks.
- The header chip shows running **median** receive delta live (`updateChip` `:2129-2152`,
  `#svp-latency` at `:256`): `recv X ms · median Y ms · n Z · write ack W ms`, warning-colored above
  1 s, bad above 4 s, and on negative deltas.

What a sub-second pass looks like: device edits a note → log line `PUSH entries/<id> … ack-lag=~0.5 s`;
the browser (already open, socket warm) shows `recv Δ < 1000 ms` in the sync log and the chip median
stays under `live 1000 ms`. From a cold new connection, expect first event ~0.5–1.2 s (TLS+upgrade
cost above); warm pushes should be well under that.

**End-to-end write→receive: NOT MEASURED.** Per-uid security rules reject every anonymous request,
and no signed-in session exists on this machine. No number here is invented. Owner procedure
(≈5 minutes, produces a real device→web number):
1. Open `https://securevaultpro.blogspot.com`, sign in, unlock live mode, add DevTools console tab,
   keep the page open (chip should read `live`).
2. On the phone: Settings → web-sync tiles — note `LISTENERS = ON`; open the Files app and confirm
   `Downloads/SecureVaultSync.txt` exists.
3. On the phone, edit any record (e.g. a note). Within seconds read in the browser sync log the
   `recv Δ…ms` line (phone→web push) and on the phone the chip/tile `PUSH LATENCY`.
4. In the browser console, run:
   `SVP_ID_TOKEN=(await firebase.auth().currentUser.getIdToken())` style export — with the modular
   SDK use `import { getAuth } from '...'; await getAuth().currentUser.getIdToken()` — copy the JWT
   into an env var on this PC (never paste it into a file).
5. On this PC: `SVP_ID_TOKEN=… node tools/sync-probe/probe.js --uid <uid> --runs 10` — it writes
   sealed synthetic records on one held-open connection and times the push the *other* connection
   receives (webchannel-style stream; logs to `sync_engine/logs/sync-probe-*.txt`). That is the
   backend's own push delay, phone-to-web parity.

## 4. Verdict

- **Architecture: confirmed push, not polling.** Both clients register persistent child listeners on
  one shared websocket-based backend connection, write via fire-and-forget `setValue`/`transaction`,
  tear down via `removeEventListener`/unsub closures, and contain no time-based data re-fetch (§2).
- **Measured transport:** TCP RTT to the Singapore edge is ~122 ms (min 118.84); a full TLS+upgrade
  connection setup is ~0.59–0.66 s (p95 ~0.67 s); on an established socket the server pushed its
  first frame ~0.4 ms after the 101. Reconnect pays the setup cost again (no TLS session reuse seen).
- **Claim status:** "millisecond sync, no polling" is *supported* by transport evidence plus a
  warm-socket push emission of <1 ms and a region floor of ~120 ms RTT; the acceptance metric
  ("median under one second device→web") is **NOT yet measured** on real clients from this machine
  and requires the §3 procedure. Honest bottom line: sub-second is highly plausible and bounded —
  the measured floor itself is ~0.12 s RTT — but the certificate is unsigned until the owner runs it.

## 5. Defects and hazards found

- **D1 — listeners never (re)attach after an in-process sign-in (defect, Android).**
  `attachForCurrentUid()` runs only from `start()` (`RealtimeEngine.kt:132`), which is latched by the
  `started` AtomicBoolean (`:119`). If the app boots before the uid exists, the engine sits at
  `waiting for sign-in` (`:258-260`); the later sign-in path calls `realtimeEngine.start()` again
  (`SettingsViewModel.kt:276`) — a no-op. Nothing observes `SvpAuth` state to re-run the attach
  (`SvpAuth.kt` has no auth-state listener; `RealtimeEngine.kt:135-142` only mirrors the uid into
  status). Result: web→phone receive silently stays OFF until process restart (phone→web push still
  works, since `pushOne` resolves the uid at write time). Fix direction: trigger `attachForCurrentUid`
  from an auth-state/uid change observation, or expose an idempotent `reattach()`.
- **D2 — no offline write queue on the web side (gap).** Android has `setPersistenceEnabled(true)`
  (`RealtimeEngine.kt:153`); the theme never calls `setPersistence` (grep: zero hits), so browser
  writes queued while offline live only in memory and are lost on tab close.
- **D3 — clock-skew LWW hazard (known, mitigated, still a hazard).** Merge order is device wall-clock
  `u` (`RealtimeEngine.kt:380`, theme `writeNode` `Date.now()` `:2226`) — a skewed sender can
  outrank real time. Mitigations: monotonic `r` tie-break (`RealtimeEngine.kt:450-480`, theme
  serverValue `:2241`), server-transaction conflict guard on web (`:2236-2249`), negative deltas
  logged not clamped (`SyncLatencyLog.kt:79-84`) and flagged in UI (`:2148-2151`).
- **Not defects (checked):** no polling anywhere (§2); the theme `setInterval` at `:862` is local
  TOTP display; Drive retry bounded at 5 (`AutoSyncManager.kt:120-128`); reconnect handled by SDK +
  `.info/connected` re-sweep (`:2028-2038`); all network awaits time-bounded (15 s Android,
  20–25 s web).
