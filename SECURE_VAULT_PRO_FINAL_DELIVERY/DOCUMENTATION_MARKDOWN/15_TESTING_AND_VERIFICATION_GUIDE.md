# SECURE VAULT PRO — Testing & Verification Guide
**Developer: Developed by Shahryar Soheail**

## Test Execution
- Unit tests: `./gradlew test` (753 passing tests).
- Release signature check: `apksigner verify --verbose <apk_path>`.
- Cryptographic test vectors: Tested against BIP-39 and RFC 6238 standard vectors.
