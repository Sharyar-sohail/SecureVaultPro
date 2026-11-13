# SECURE VAULT PRO — Known Issues & Fixes
**Developer: Developed by Shahryar Soheail**

1. **Gradle Build Cache File Locks on Windows**: Fixed by running `gradlew --stop` and cleaning intermediates.
2. **Drive API Timeout on Slow Networks**: Resolved via 120s extended timeout watchdog and asynchronous queueing.
3. **Hardware Keystore Downgrade on OEM Devices**: Addressed with fallback to PBKDF2 device-bound key derivation.
