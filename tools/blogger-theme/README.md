# Secure Wallet Pro — Blogger / Blogspot companion theme generator

This folder contains a zero-dependency Node.js utility that turns a passphrase-sealed
Secure Wallet Pro export into a complete **Blogger Layouts v2 XML theme**. The theme
renders a web vault that opens with Google sign-in and the vault passphrase: browse and search
every module, reveal/copy secrets, generate live TOTP codes, download encrypted
attachments, edit logins, seal an updated archive, and optionally browse/save sealed files
in Google Drive. The offline file picker additionally opens a backup the owner sealed with a
password of their own choosing when exporting it.

The app can also produce the same file directly (**Backup → Export Blogger XML**); this
generator is the desktop equivalent. The APK bundles the XML template, not the Node.js generator.

---

## Requirements

- Node.js 18 or newer (no npm install needed).
- A passphrase-sealed export from the app: **Backup → Export encrypted backup**.
  That produces `SecureWallet_Backup_YYYYMMDD_HHMMSS.enc` (envelope v3 JSON).

> The automatic `vault_backup*.enc` that the app mirrors to Drive is sealed with **your own
> account's vault key** — the same 32 bytes the keyring publishes and the page unwraps after
> Google sign-in. Embedding it in a public theme is still pointless and wrong: the theme would
> carry a copy of your vault that only your Google session can open, and every visitor would
> download that ciphertext. Embed a file you exported yourself, or none at all.

## Generate the theme

```bash
cd tools/blogger-theme

node generate.js \
  --in "C:\path\to\SecureWallet_Backup_20261026_120000.enc" \
  --out ../upload/blogger_theme.xml \
  --title "My Secure Vault" \
  --drive-client-id "1234567890-abcdef.apps.googleusercontent.com"
```

Write the upload copy somewhere else than this folder: the generator refuses to overwrite
`secure_wallet_blogger_theme.xml`, because that file is the source template and must stay free of
injected project values. `app/src/main/assets/blogger_theme.xml` is the opposite: it is a *generated*
file, rebuilt with the project's own `google-services.json`, because the app's own theme export starts
from that asset and can only add the snapshot — a bare template there would ship an empty web key and
the page would never draw its Google button. The repo test regenerates and compares it byte for byte.

Options:

| Flag | Purpose |
| --- | --- |
| `--in <file>` | Passphrase-sealed `.enc` export to embed (ciphertext only). Omit to build a theme that unlocks a file picked in the browser. |
| `--out <file>` | Output path. Default `secure_wallet_blogger_theme.xml`. |
| `--title <text>` | Header title on the page. |
| `--drive-client-id <id>` | OAuth 2.0 **Web** Client ID for the optional Google Drive panel. |
| `--google-services <file>` | `google-services.json` of the app project. Default `../../app/google-services.json`; the realtime wiring (project id, RTDB url, web client id, public web API key) is read from it. |
| `--package <id>` | Package to pick out of `google-services.json` when several apps share the file. Default `com.securevault.pro`. |
| `--firebase-api-key <key>` | Override the public web API key taken from `google-services.json`. Validated (`AIza…`); anything shaped like a secret is rejected. |
| `--rtdb-region <region>` | Realtime Database region used to build the URL when the JSON carries none. Default `asia-southeast1`. |
| `--database-url <url>` | Explicit `https://` RTDB URL (only https is accepted). |
| `--firebase-version <ver>` | Firebase JS version loaded from `www.gstatic.com`. Default `10.12.2`. |

No secret value is hardcoded in the generator or the template: the API key and client ID
come from `google-services.json`, and both are public-by-design web identifiers. The
passphrase never appears in the theme, in the generator, or in any log.

```bash
node --test tools/blogger-theme/generate.test.js   # asset parity + contract checks
```

## Realtime mode (same backend as the app)

Press **Sign in with Google** at the top of the page: the ID token is exchanged for a
Firebase session with the *same uid* the phone uses, so both frontends read and write one
database. This is the proof that one backend serves two clients — a change on the phone
appears on the page in well under a second, with no reload, no polling and no copy-paste.

Prerequisite, once per account, in the app: **Settings → Realtime vault key → Publish** with
the vault passphrase. That writes `svp1/users/<uid>/keyring`, which holds the *vault key*
wrapped by a passphrase-derived key — never the passphrase itself. On the web the wrap is
opened with WebCrypto (PBKDF2-SHA256 using the node's own `salt`/`iters`, then AES-256-GCM),
and the resulting key lives in memory only.

Then on the page:

- type the same vault passphrase and press **Unlock live**;
- the seven module listeners (`onChildAdded` / `onChildChanged` / `onChildRemoved`) stream
  `{e,u,r,d}` nodes; each `e` is `base64(iv).base64(ciphertext+tag)` and is decrypted before
  `JSON.parse`;
- edits are re-sealed and written inside an RTDB transaction, with `r` taken from
  `serverValue()` and `u = Date.now()`; the conflict rule is byte-for-byte the app's §4 rule;
- the chip shows the measured median of `tReceive - u` for changes that came from the other
  device, plus the write-ack median. Every sample is appended to `localStorage['svp-sync-log']`
  and is readable through **Sync log**. Numbers are measured, never asserted;
- **Files** stay metadata-only: the database never holds bytes, so previews fetch through the
  user's own Drive session on demand (there is no base64 path in the realtime flow);
- **Pause live** / **Sign out** drop the key and detach the listeners; a lost connection shows
  **reconnecting** and reconciles on its own when it returns.

## Publish on Blogger

1. Open [Blogger](https://www.blogger.com) → your blog → **Theme**.
2. Menu (⋮) → **Backup / Restore** first if you want to keep the current theme.
3. **Customise** → **Edit HTML**.
4. Select everything and replace it with the generated XML, then **Save**.
5. Visit the blog. The vault appears at the top of the page.

If you regenerate after vault changes, replace the HTML again and save.

### Blank page after saving

Blogger publishes a `<b:widget>` whose `b:includable` has no `<b:widget-settings>` as an
**empty section**, which leaves only the background. Because of that, this theme keeps no
vault markup in the body at all: the whole interface is built at runtime by the script
(which Blogger preserves), and the encrypted snapshot is embedded in the `var SEED` line.
Only `<b:section/>` and the script remain in `<body>`. Do not move the UI back into a
`<b:widget>`.

## Using the web vault

- **Unlock** — the vault key route comes first.
  *Realtime and the app's own Drive backups*: sign in with Google and unlock with the vault
  passphrase, which opens the published keyring (see **Realtime mode**). The same unwrapped
  vault key is what the app stretches to seal `vault_backup*.enc`, so those copies open here
  with no further typing — the page never asks a human for a backup password for them.
  *Sealed file*: a copy the owner protected with a password of their own in Backup opens with
  that password; PBKDF2-SHA256 (600,000 rounds) + AES-256-GCM run locally via WebCrypto.
  Every key is derived in the browser and never travels; the Google session only fetches
  ciphertext, so no design ever publishes a shared cloud key.
- **Modules** — Logins, Cards, 2FA, Notes, Contacts, Crypto wallets and Files, with global search.
- **TOTP** — codes refresh every second using the stored secret, digits and period.
- **Files** — realtime entries preview/download through your own Drive session; attachments
  stored inside a sealed envelope download in plain form.
- **Editing** — add, edit or delete logins (and other items). In realtime mode every save is
  sealed and written to the database at once, so it lands on the phone; in sealed-file mode
  changes live only in the tab until you press **Save encrypted copy**, which re-seals the
  full payload with a fresh salt/IV and downloads `secure_wallet_web.enc`.
- **Round-trip** — in the app use **Backup → Import**, choose `secure_wallet_web.enc`
  and enter the same passphrase.
- **Format note** — password-protected XML payloads inside envelope v3 are supported;
  automatic cloud-key-encrypted XML mirrors are not. Saving produces a JSON envelope v3.

## Optional Google Drive panel

The **Sign in with Google** button uses Google Identity Services (GIS) to obtain a
short-lived access token, then calls the Drive REST v3 API from the browser. It lists
sealed files in the app's hidden `appDataFolder`, opens one for unlocking, and can save
`secure_wallet_web.enc` back to that folder.

Signing in is the whole access step for a backup the app wrote on its own: those copies are
sealed with your account's vault key, which the page already holds after you sign in with
Google and enter the vault passphrase, so it opens them without asking for any backup
password. A copy you sealed with a password of your own when exporting it still needs that
password, because a Drive token can fetch ciphertext but cannot derive that key.

Setup:

1. The template includes the Web OAuth client listed as `client_type: 3` in the app's
   `google-services.json`. Keep Android and Web clients in that same Google Cloud project.
2. Enable **Google Drive API** in that project.
3. Open the existing **OAuth 2.0 Client ID → Web application**.
4. Add `https://securevaultpro.blogspot.com` to **Authorised JavaScript origins**.
   The **Google setup** panel displays the exact origin of the current page.
5. To override the default client, pass `--drive-client-id <id>` when generating the XML,
   or open **Connect Drive → Google setup**, enter the ID and press **Save**, then
   **Sign in with Google**. The public ID is stored locally; tokens and passwords are not.
   The setup panel remains available if a client is rejected or the origin is not allowed.

The repository records the client ID, not Google's current allowed origins, consent
screen or test-user settings. Those settings must be verified in Google Cloud before
live sign-in can be considered configured.

A client ID is a public identifier; no secret is used in the browser. An **Android**
client ID will not work here — GIS only accepts a *Web application* client whose
authorised JavaScript origin matches the page.

The `vault_backup.enc`, `vault_backup.xml.enc` and timestamped sync filenames are the app's
own automatic copies, sealed with **your account's vault key** — the keyring secret the page
unwraps after Google sign-in, never a key shipped in the theme. The filename is only the hint
for a copy written before the file itself named its key; a copy that says `kid: vault` is
recognised from the file. So these open in the Drive browser as soon as the keyring is
unlocked, and no export password is ever asked for one. What remains on the typed-password
route is a copy the owner sealed with their own password in **Backup → Export encrypted
backup** (`SecureWallet_*.enc`, `kid: passphrase`). Local export does not upload that file to
the hidden Drive folder automatically. Web saves use `secure_wallet_web.enc`.

Notes:

- A Web Client ID is public by design; no client secret is used in the browser.
- `appDataFolder` is scoped to the Google Cloud project. A Web client from a different
  project cannot see the Android app's files. Keep both clients in the same project.
- The Drive panel requests `drive.appdata` and `drive.file` only. It never requests full
  Drive access.
- The Drive token stays in page memory and is discarded when the tab closes.

## Pair with the phone

The **Pair with the app** card draws its own QR code, generated in-page by a small
built-in encoder (no third-party image service, nothing leaves the browser). The code
carries exactly one line:

```
securewallet://pair?u=<this page's origin>&c=<six characters>
```

In the app: **Settings → Pair browser mirror → Scan site QR**. The app stores the address
and adopts the six characters, so pairing works even before any companion address was
typed in the app. The other direction still works: type the app's code into the site's
input, or use **New code** to issue a fresh one. **Unpair** clears both the stored pairing
and the issued code.

The six characters are a presence handshake only — never a key, passphrase or record. They
let the page mark itself as "this is my browser"; nothing decrypts without the vault key this
page holds after Google sign-in, or the password the owner typed for that particular export.
Codes use the alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no I, O, 0, 1).

## Security model

- Theme source contains ciphertext only — no secrets, no passphrase, no universal key.
- Realtime mode adds nothing to that rule: the bundle carries a *public* Firebase web API key
  and OAuth client ID (read from `google-services.json` at build time) plus the wrapped vault
  key that only your passphrase can open. An unopened wrap decrypts nothing, and the database
  rules only serve nodes to a session whose uid equals the node path.
- Every remote endpoint in the theme is `https:` — Firebase modules, Google Identity Services
  and the Realtime Database. TLS is never relaxed and no cleartext fallback exists.
- All rendering uses `textContent`/DOM nodes, so vault values cannot inject HTML or
  script into the page.
- Decryption and encryption happen locally in the browser; there is no backend.
- Anyone with the theme file and the passphrase can decrypt it. Treat the published blog
  as a copy of your vault: use a strong, unique passphrase and remove the theme if the
  blog is compromised.
- Blogger itself, browser extensions, or XSS elsewhere on the blog could observe the
  decrypted DOM while unlocked. Keep the blog free of third-party widgets if you plan to
  unlock it there.

## Files

| File | Description |
| --- | --- |
| `generate.js` | Zero-dependency generator (Node ≥ 18). |
| `secure_wallet_blogger_theme.xml` | Ready-to-upload starter theme (no embedded snapshot; unlock via file picker or Drive). |
| `README.md` | This document. |
