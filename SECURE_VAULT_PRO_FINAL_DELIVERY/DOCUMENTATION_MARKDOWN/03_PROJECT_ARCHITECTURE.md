# SECURE VAULT PRO — System Architecture
**Developer: Developed by Shahryar Soheail**

```
+-------------------------------------------------------------+
|                     SECURE VAULT PRO                        |
|           Presentation Layer (Jetpack Compose + M3)         |
+-------------------------------------------------------------+
                              |
+-------------------------------------------------------------+
|               Domain Layer & Business Logic                 |
|  (VaultInteractors, CryptoDerivers, SyncEngine, TOTPEngine) |
+-------------------------------------------------------------+
                              |
+-------------------------------------------------------------+
|                 Data & Cryptographic Layer                  |
|  (Room SQLite Encrypted, Android Keystore, AES-256-GCM)     |
+-------------------------------------------------------------+
         |                                           |
+-----------------------+                 +--------------------+
|  Google Drive AppData |                 | Blogger XML Engine |
+-----------------------+                 +--------------------+
```

### Module Breakdown
- `:app`: Main Android application containing UI, ViewModels, Room DAOs, and Security Services.
- `sync_engine`: Cross-platform contract specification, JSON envelopes, and cryptographic vectors.
- `tools/blogger-theme`: Responsive Blogger XML generator with theme bridge and palette synchronization.
