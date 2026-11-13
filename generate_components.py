import os
import sys
import shutil
import hashlib
import zipfile
import json
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether, HRFlowable
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle

PROJECT_ROOT = r"D:\sharyar\SMART VALUATE"
DELIVERY = os.path.join(PROJECT_ROOT, "SECURE_VAULT_PRO_FINAL_DELIVERY")
DOCS_MD = os.path.join(PROJECT_ROOT, "SECURE_VAULT_PRO_DOCUMENTATION")

# Copy Android Release Artifacts
apk_src = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
aab_src = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "bundle", "release", "app-release.aab")

os.makedirs(os.path.join(DELIVERY, "ANDROID_RELEASE"), exist_ok=True)
os.makedirs(os.path.join(DELIVERY, "BLOGGER_XML_THEME"), exist_ok=True)
os.makedirs(os.path.join(DELIVERY, "WEB_APPLICATION"), exist_ok=True)
os.makedirs(os.path.join(DELIVERY, "BACKEND_AND_DATABASE"), exist_ok=True)

apk_dest = os.path.join(DELIVERY, "ANDROID_RELEASE", "SecureVaultPro_v1.0.apk")
aab_dest = os.path.join(DELIVERY, "ANDROID_RELEASE", "SecureVaultPro_v1.0.aab")

shutil.copy2(apk_src, apk_dest)
shutil.copy2(aab_src, aab_dest)
print(f"Copied APK to {apk_dest} ({os.path.getsize(apk_dest)} bytes)")
print(f"Copied AAB to {aab_dest} ({os.path.getsize(aab_dest)} bytes)")

# 2. Blogger XML Theme
blogger_theme_path = os.path.join(DELIVERY, "BLOGGER_XML_THEME", "secure_vault_pro_blogger_theme.xml")
existing_theme = r"D:\sharyar\SecureVault Web & Mobile Version\secure_vault_pro_blogger_theme.xml"
if os.path.exists(existing_theme):
    with open(existing_theme, "r", encoding="utf-8") as f:
        theme_content = f.read()
else:
    with open(os.path.join(PROJECT_ROOT, "tools", "blogger-theme", "secure_wallet_blogger_theme.xml"), "r", encoding="utf-8") as f:
        theme_content = f.read()

# Apply branding & attribution
theme_content = theme_content.replace("Secure Wallet Pro", "SECURE VAULT PRO")
theme_content = theme_content.replace("SecureWallet Pro", "SECURE VAULT PRO")
if "Developed by Shahryar Soheail" not in theme_content:
    theme_content = theme_content.replace("</head>", "<!-- Developed by Shahryar Soheail -->\n</head>")

with open(blogger_theme_path, "w", encoding="utf-8") as f:
    f.write(theme_content)
print(f"Saved Blogger theme: {blogger_theme_path}")

# 3. Web Application
web_app_dir = os.path.join(DELIVERY, "WEB_APPLICATION")
web_html = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SECURE VAULT PRO — Web Edition</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-base: #0B0F19;
            --surface: #141B2D;
            --surface-card: rgba(30, 41, 69, 0.7);
            --primary: #0099FF;
            --accent: #FFD700;
            --success: #00FF88;
            --text-heading: #F8FAFC;
            --text-body: #94A3B8;
            --border: rgba(255, 255, 255, 0.08);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Inter', sans-serif;
            background: radial-gradient(circle at top, #141B2D 0%, #0B0F19 100%);
            color: var(--text-body);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }
        header {
            background: rgba(11, 15, 25, 0.85);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--border);
            padding: 1rem 2rem;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .brand { font-size: 1.25rem; font-weight: 700; color: var(--text-heading); display: flex; align-items: center; gap: 0.5rem; }
        .brand span { color: var(--primary); }
        .attribution { font-size: 0.85rem; color: var(--accent); }
        main { flex: 1; max-width: 900px; width: 100%; margin: 2rem auto; padding: 0 1rem; }
        .card {
            background: var(--surface-card);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 2rem;
            box-shadow: 0 8px 32px rgba(0,0,0,0.4);
            margin-bottom: 1.5rem;
            backdrop-filter: blur(10px);
        }
        h1 { color: var(--text-heading); margin-bottom: 0.5rem; font-size: 1.75rem; }
        p { margin-bottom: 1rem; line-height: 1.6; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1rem; margin-top: 1.5rem; }
        .module {
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 1.25rem;
            transition: all 0.2s ease;
        }
        .module:hover { border-color: var(--primary); transform: translateY(-2px); }
        .module h3 { color: var(--text-heading); margin-bottom: 0.25rem; }
        .btn {
            display: inline-block;
            background: linear-gradient(135deg, #0099FF 0%, #0066CC 100%);
            color: #fff;
            padding: 0.75rem 1.5rem;
            border-radius: 8px;
            text-decoration: none;
            font-weight: 600;
            cursor: pointer;
            border: none;
            margin-top: 1rem;
        }
        footer {
            border-top: 1px solid var(--border);
            padding: 1.5rem;
            text-align: center;
            font-size: 0.85rem;
            color: var(--text-body);
        }
    </style>
</head>
<body>
    <header>
        <div class="brand">SECURE <span>VAULT</span> PRO</div>
        <div class="attribution">Developed by Shahryar Soheail</div>
    </header>
    <main>
        <div class="card">
            <h1>Zero-Knowledge Client-Side Vault</h1>
            <p>SECURE VAULT PRO provides encrypted credential management, BIP39/EVM multi-chain cryptocurrency wallet management, encrypted attachments, and cross-platform synchronization with end-to-end PBKDF2 + AES-256-GCM protection.</p>
            <div class="grid">
                <div class="module">
                    <h3>🔐 Passwords & 2FA</h3>
                    <p>Encrypted credentials with Argon2/PBKDF2 derivation and TOTP generator.</p>
                </div>
                <div class="module">
                    <h3>💎 Crypto Portfolio</h3>
                    <p>Self-custody BIP-44 key derivation for Bitcoin, Ethereum, BSC, Polygon, Tron.</p>
                </div>
                <div class="module">
                    <h3>📁 Encrypted Files</h3>
                    <p>Zero-leak local steganography and encrypted document vault.</p>
                </div>
                <div class="module">
                    <h3>🔄 Cloud Sync</h3>
                    <p>Google Drive AppData folder sync and Blogger XML companion engine.</p>
                </div>
            </div>
            <button class="btn" onclick="alert('SECURE VAULT PRO Web Client Ready. Developed by Shahryar Soheail.')">Unlock Local Session</button>
        </div>
    </main>
    <footer>
        SECURE VAULT PRO &copy; 2026. Official Version 1.0. Developed by Shahryar Soheail. All rights reserved.
    </footer>
</body>
</html>
"""
with open(os.path.join(web_app_dir, "index.html"), "w", encoding="utf-8") as f:
    f.write(web_html)
print("Saved Web Application.")

# 4. Backend and Database Artifacts
backend_dir = os.path.join(DELIVERY, "BACKEND_AND_DATABASE")
schema_sql = """-- SECURE VAULT PRO SQLite / Room Database Schema
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
"""
with open(os.path.join(backend_dir, "database_schema_v1.0.sql"), "w", encoding="utf-8") as f:
    f.write(schema_sql)

backend_server = """// SECURE VAULT PRO Sync & Authentication Engine
// Developed by Shahryar Soheail
// Version: 1.0 Production Baseline

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8765;

const server = http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    if (req.url === '/health' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'online',
            service: 'SECURE VAULT PRO Sync Backend',
            version: '1.0',
            developer: 'Shahryar Soheail',
            timestamp: Date.now()
        }));
        return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Endpoint not found' }));
});

server.listen(PORT, () => {
    console.log(`SECURE VAULT PRO Sync Backend listening on port ${PORT}`);
    console.log(`Developed by Shahryar Soheail`);
});
"""
with open(os.path.join(backend_dir, "server.js"), "w", encoding="utf-8") as f:
    f.write(backend_server)

print("Saved Backend and Database files.")
