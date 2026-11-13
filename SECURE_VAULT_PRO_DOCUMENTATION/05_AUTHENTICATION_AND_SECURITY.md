# SECURE VAULT PRO — Cryptographic & Security Architecture
**Developer: Developed by Shahryar Soheail**

## 1. Master Key Derivation
- Algorithm: PBKDF2 with HMAC-SHA256
- Iteration Count: 600,000 rounds
- Salt: 32-byte cryptographically secure random salt generated via `SecureRandom`
- Output: 256-bit Key Derivation Key (KDK)

## 2. Vault Encryption (AES-256-GCM)
- Cipher: `AES/GCM/NoPadding`
- Key Length: 256 bits
- IV: 12-byte unique initialization vector per encrypted record
- Tag Length: 128-bit authentication tag for integrity verification

## 3. Android Keystore Hardware Isolation
- Hardware-backed master key storage using StrongBox Keymaster where available.
- Keys require biometric authentication (`BiometricPrompt`) or device lock credential.

## 4. Anti-Tamper & Memory Security
- `FLAG_SECURE` enabled by default to prevent screen recording and task switcher leaks.
- Zeroing of sensitive char arrays and byte buffers immediately after cryptographic execution.
