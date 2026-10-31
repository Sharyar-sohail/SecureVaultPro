"""Generate legacy backup envelopes with an implementation independent of the app.

This exists so the unit tests can prove the Kotlin opener reads files written by an
old build, rather than proving that Kotlin can read its own output.

Shapes produced (all with PBKDF2-HMAC-SHA256 + AES-256-GCM, 12-byte IV):
  A. iv.payload            v:3, 600,000 rounds, retired universal key  (the last shipped legacy writer)
  B. iv.payload            v:3, 210,000 rounds, retired universal key  (PIN profile)
  C. iv.payload            v:3, 120,000 rounds, retired universal key  (earlier profile)
  D. iv.payload            v:3, 600,000 rounds, owner passphrase      (export sealed by passphrase)
  E. iv.payload            v:3, 600,000 rounds, account address       (earliest writer)
  F. iv.ciphertext.tag     v:2, 600,000 rounds, retired universal key  (separate tag shape)
  G. iv.tag.ciphertext     v:2, 600,000 rounds, retired universal key  (halves swapped)
  H. iv.payload            v:3, 600,000 rounds, universal key, body base64-wrapped at 76 cols
"""
import base64
import hashlib
import json
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PLAINTEXT = (
    '{"version":3,"exportedAt":1712345678901,"passwords":[{"id":"p-1","title":"Bank",'
    '"username":"owner@example.com","password":"S3cret-Passw0rd!","url":"https://bank.example",'
    '"notes":"legacy record","createdAt":1700000000000}],"contacts":[{"id":"c-1",'
    '"name":"Ada Lovelace","phone":"+15550100","email":"ada@example.com"}],'
    '"notes":[{"id":"n-1","title":"Recovery","body":"legacy note body"}]}'
)

UNIVERSAL_V3 = "SecureVaultPro_Universal_Cloud_Key_v3"
UNIVERSAL_V2 = "SecureVaultPro_Universal_Cloud_Key_v2"
UNIVERSAL_V1 = "SecureVaultPro_Universal_Cloud_Key_v1"
UNIVERSAL_V0 = "SecureVaultPro_Universal_Cloud_Key"
OWNER_PASSPHRASE = "correct horse battery staple"
OWNER_EMAIL = "owner@example.com"


def seal(secret, salt, iv, iterations):
    key = hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, iterations, 32)
    blob = AESGCM(key).encrypt(iv, PLAINTEXT.encode("utf-8"), None)
    return blob  # ciphertext || 16-byte tag


def build(name, version, secret, iterations, shape):
    salt = hashlib.sha256((name + "-salt").encode()).digest()  # deterministic 32 bytes
    iv = hashlib.sha256((name + "-iv").encode()).digest()[:12]
    blob = seal(secret, salt, iv, iterations)
    ct, tag = blob[:-16], blob[-16:]
    if shape == "iv.payload":
        body = base64.b64encode(iv).decode() + "." + base64.b64encode(blob).decode()
    elif shape == "iv.ciphertext.tag":
        body = base64.b64encode(iv).decode() + "." + base64.b64encode(ct).decode() + "." + base64.b64encode(tag).decode()
    elif shape == "iv.tag.ciphertext":
        body = base64.b64encode(iv).decode() + "." + base64.b64encode(tag).decode() + "." + base64.b64encode(ct).decode()
    else:
        raise ValueError(shape)
    envelope = json.dumps({"v": version, "salt": base64.b64encode(salt).decode(), "data": body},
                          separators=(",", ":"))
    wrapped = shape == "iv.payload" and name.startswith("H")
    blob_out = envelope.encode()
    if wrapped:
        # Old Android Base64.DEFAULT wrapped the whole envelope at 76 columns and added CRLF.
        encoded = base64.encodebytes(blob_out).decode()  # wraps at 76 chars with \n
        blob_out = encoded.encode()
    return {
        "name": name,
        "secret": secret,
        "iterations": iterations,
        "shape": shape,
        "envelopeBase64": base64.b64encode(blob_out).decode(),
    }


vectors = [
    build("A-universal-v3-600k", 3, UNIVERSAL_V3, 600_000, "iv.payload"),
    build("B-universal-v3-210k", 3, UNIVERSAL_V3, 210_000, "iv.payload"),
    build("C-universal-v3-120k", 3, UNIVERSAL_V3, 120_000, "iv.payload"),
    build("D-passphrase-600k", 3, OWNER_PASSPHRASE, 600_000, "iv.payload"),
    build("E-email-600k", 3, OWNER_EMAIL, 600_000, "iv.payload"),
    build("F-universal-v2-tagged", 2, UNIVERSAL_V2, 600_000, "iv.ciphertext.tag"),
    build("G-universal-v2-swapped", 2, UNIVERSAL_V2, 600_000, "iv.tag.ciphertext"),
    build("H-universal-v0-wrapped", 3, UNIVERSAL_V0, 600_000, "iv.payload"),
    build("I-universal-v1-600k", 3, UNIVERSAL_V1, 600_000, "iv.payload"),
]

out = {"plaintext": PLAINTEXT, "vectors": vectors}
here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(here, "legacy_vectors.json"), "w", newline="\n") as fh:
    json.dump(out, fh, indent=2)
print("wrote %d vectors" % len(vectors))
print("plaintext sha256:", hashlib.sha256(PLAINTEXT.encode()).hexdigest())
