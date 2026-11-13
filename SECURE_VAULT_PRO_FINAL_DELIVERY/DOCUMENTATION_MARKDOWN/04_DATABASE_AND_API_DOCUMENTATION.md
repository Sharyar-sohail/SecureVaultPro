# SECURE VAULT PRO — Database & API Specifications
**Developer: Developed by Shahryar Soheail**

## SQLite / Room Database Entities
1. **`entries` Table**: Stores encrypted passwords, credentials, categories, and audit timestamps.
2. **`crypto_wallets` Table**: Stores BIP-39 mnemonic seeds and private keys encrypted with device key.
3. **`totp_records` Table**: Stores 2FA authenticators, secrets, periods (30s), and algorithm (SHA1/SHA256).
4. **`encrypted_files` Table**: Stores local attachment paths, MIME types, and SHA-256 integrity hashes.
5. **`audit_logs` Table**: Tracks security events, failed unlock attempts, and export actions.

## API & Sync Endpoints
- Google Drive API v3: `https://www.googleapis.com/drive/v3/files` (AppDataFolder sandbox).
- Blockchain Explorers: Public RPC endpoints for Ethereum, BSC, Polygon, TRON, and Bitcoin balance queries.
