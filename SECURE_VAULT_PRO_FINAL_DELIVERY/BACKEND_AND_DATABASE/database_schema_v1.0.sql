-- SECURE VAULT PRO SQLite / Room Database Schema
-- Version 1.0 Production Release
-- Namespace: com.securevault.pro

CREATE TABLE IF NOT EXISTS entries (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    username TEXT NOT NULL,
    password_enc BLOB NOT NULL,
    iv BLOB NOT NULL,
    tag BLOB NOT NULL,
    url TEXT,
    notes TEXT,
    category TEXT NOT NULL,
    favorite INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS crypto_wallets (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    seed_phrase_enc BLOB,
    private_key_enc BLOB,
    address TEXT NOT NULL,
    network_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS totp_records (
    id TEXT PRIMARY KEY NOT NULL,
    label TEXT NOT NULL,
    issuer TEXT,
    secret_enc BLOB NOT NULL,
    digits INTEGER NOT NULL DEFAULT 6,
    period INTEGER NOT NULL DEFAULT 30,
    algorithm TEXT NOT NULL DEFAULT 'SHA1'
);

CREATE TABLE IF NOT EXISTS encrypted_files (
    id TEXT PRIMARY KEY NOT NULL,
    filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    encrypted_blob_path TEXT NOT NULL,
    sha256_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    screen_name TEXT NOT NULL,
    details TEXT
);
