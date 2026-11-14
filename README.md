# SECURE VAULT PRO

**Official Production Release — Version 1.0**  
**Developed by Shahryar Soheail**  
**Package ID:** `com.securevault.app` | **Namespace:** `com.securevault.pro`

[![GitHub Release](https://img.shields.io/badge/Release-Version%201.0-blue.svg)](https://github.com/Sharyar-sohail/SecureVaultPro)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Web%20%7C%20Blogger-green.svg)](https://github.com/Sharyar-sohail/SecureVaultPro)
[![Security](https://img.shields.io/badge/Security-AES--256--GCM%20%7C%20PBKDF2%20600k-red.svg)](https://github.com/Sharyar-sohail/SecureVaultPro)

---

## 📖 Overview
**SECURE VAULT PRO** is an enterprise-grade, zero-knowledge cryptographic mobile and web security suite. It delivers client-side authenticated encryption for credentials, financial cards, 2FA/TOTP authenticator tokens, encrypted attachments, and self-custody multi-chain cryptocurrency assets (Bitcoin, Ethereum, Binance Smart Chain, Polygon, and TRON).

---

## 🤖 Instant Project Resumption for AI Agents & Developers
If you clone this repository or open it in **AntiGravity IDE**, Cursor, Claude Code, or VS Code:
1. **Developer Attribution:** All future iterations MUST retain **"Developed by Shahryar Soheail"**.
2. **Master Directive:** Read [AI_DEVELOPMENT_DIRECTIVE.md](AI_DEVELOPMENT_DIRECTIVE.md) and [SECURE_VAULT_PRO_MASTER_CONTROL_PROMPT.txt](SECURE_VAULT_PRO_MASTER_CONTROL_PROMPT.txt) for immediate context.
3. **Build Command:**
   ```bash
   ./gradlew clean assembleRelease bundleRelease
   ```
4. **Configuration Templates:** Copy `keystore.properties.example` to `keystore.properties` for custom signed builds.

---

## 🏛️ Architecture & Directory Structure
- **`app/`**: Android application built with Jetpack Compose, Kotlin 2.x, Room SQLite, Hilt, and Material3.
- **`SECURE_VAULT_PRO_FINAL_DELIVERY/`**:
  - `ANDROID_RELEASE/`: Production release artifacts (`SecureVaultPro_v1.0.apk` & `SecureVaultPro_v1.0.aab`).
  - `WEB_APPLICATION/`: Zero-knowledge responsive web edition.
  - `BLOGGER_XML_THEME/`: Responsive Blogger companion XML theme.
  - `BACKEND_AND_DATABASE/`: Node.js sync server and SQLite database schemas.
  - `SOURCE_CODE_PACKAGES/`: Modular ZIP packages with SHA-256 checksums.
  - `INDIVIDUAL_PROMPTS/`: 14 modular, reusable development prompts.
  - `MASTER_PROMPT/`: Master prompt in TXT, DOCX, and PDF formats.
- **`SECURE_VAULT_PRO_DOCUMENTATION/`**: 18 complete technical architecture, API, security, and testing guides.

---

## 🔐 Cryptographic Standards
- **Authenticated Encryption:** AES-256-GCM (`AES/GCM/NoPadding`) with unique 12-byte IV per record.
- **Key Stretching:** PBKDF2 with HMAC-SHA256 across 600,000 iterations.
- **Hardware Isolation:** Android Keystore StrongBox Keymaster integration.
- **Multi-Chain Derivation:** BIP-39 mnemonic phrases, BIP-44 path derivation, and EIP-55 checksum addresses.
- **Biometric Security:** BiometricPrompt with master password fallback and brute-force throttling.

---

## 👤 Developer & Legal Attribution
**Developed by Shahryar Soheail**  
*Official Version 1.0 Production Release. All rights reserved.*
