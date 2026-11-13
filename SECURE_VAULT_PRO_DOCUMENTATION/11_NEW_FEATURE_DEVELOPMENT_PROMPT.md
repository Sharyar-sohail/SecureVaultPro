# SECURE VAULT PRO — New Feature Development Prompt
**Developer: Developed by Shahryar Soheail**

Guidelines for adding new features:
1. Feature must follow Clean Architecture (ViewModel -> Repository -> Encrypted DAO).
2. Any new database entity must be encrypted using `VaultCrypto` before writing to disk.
3. UI components must utilize `GlassScaffold` and `VaultColor` palette tokens.
