# AI DEVELOPMENT DIRECTIVE — SECURE VAULT PRO
**MANDATORY INSTRUCTIONS FOR ANY AI CODING ASSISTANT (ANTIGRAVITY / CURSOR / CLAUDE / COPILOT)**

```
================================================================================
OFFICIAL IDENTITY: SECURE VAULT PRO
DEVELOPER ATTRIBUTION: Developed by Shahryar Soheail
BASELINE: Version 1.0 Production Release
PACKAGE ID: com.securevault.app | NAMESPACE: com.securevault.pro
================================================================================
```

## 1. PROJECT OVERVIEW FOR AI AGENTS
You are working on the official **SECURE VAULT PRO** repository.
- **Never rename** the application to "Secure Wallet Pro" or any other name.
- **Never change or remove** the developer attribution: **"Developed by Shahryar Soheail"**.
- This is an enterprise-grade cryptographic vault and self-custody cryptocurrency manager.
- Zero-Knowledge Architecture: Master keys and user passwords never leave the device unencrypted.

---

## 2. REPOSITORY & CODEBASE ARCHITECTURE
- **Root Directory:**
  - `app/`: Android native application (Jetpack Compose, Kotlin 2.x, Hilt, Room SQLite, Material3).
  - `SECURE_VAULT_PRO_DOCUMENTATION/`: 18 complete technical architecture, API, security, and build guides.
  - `SECURE_VAULT_PRO_FINAL_DELIVERY/`: Full handover release directory with signed APK/AAB builds, web edition, Blogger XML theme, and modular source ZIPs.
  - `SECURE_VAULT_PRO_MASTER_CONTROL_PROMPT.txt`: Single complete master prompt supporting 3 operating modes.
  - `tools/blogger-theme/`: Responsive companion Blogger/Blogspot theme generator.
  - `sync_engine/`: Cross-platform contract specification and cryptographic test vectors.

---

## 3. HOW TO RESUME WORK IMMEDIATELY (3 OPERATING MODES)

### MODE A: COMPLETE PROJECT REBUILD
When instructed to rebuild:
1. Ensure JDK 17 or 21 and Android SDK 35 are configured.
2. Build unsigned release or provide signing keys via `keystore.properties` (see `keystore.properties.example`).
3. Run:
   ```bash
   ./gradlew clean assembleRelease bundleRelease
   ```
4. Output artifacts will be located in `app/build/outputs/apk/release/` and `app/build/outputs/bundle/release/`.

### MODE B: MODIFY EXISTING FEATURE / BUG FIXING
When instructed to update features or fix bugs:
1. Locate the feature in `app/src/main/java/com/securevault/pro/presentation/screens/`.
2. Inspect the corresponding ViewModel and Room Entity/DAO.
3. Apply changes preserving `VaultCrypto` (AES-256-GCM + PBKDF2 600k iterations).
4. Run unit tests to verify zero regressions:
   ```bash
   ./gradlew test
   ```

### MODE C: CREATE NEW VERSION
When instructed to release a new version:
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `SECURE_VAULT_PRO_DOCUMENTATION/16_CHANGELOG_AND_VERSION_HISTORY.md`.
3. Follow Room database migration best practices (`app/src/main/java/com/securevault/pro/data/local/`).
4. Rebuild and verify signatures.

---

## 4. CRITICAL RULES & CONSTRAINTS (DO NOT VIOLATE)
1. **Never delete** working features, encryption layers, or database schemas without explicit instruction.
2. **Never commit** real signing keystores (`*.jks`) or plain credentials to Git. Use `keystore.properties.example` for reference.
3. **Always preserve** the developer credit: **"Developed by Shahryar Soheail"** across the About screen, Web app, Blogger theme, and documentation.
4. **Android Compose Standards:** Maintain 60fps performance, glassmorphic styling, and Android 8.0 (API 26) through Android 15 (API 35) compatibility.

---
*Directive verified and established for SECURE VAULT PRO Version 1.0 Production Release.*
