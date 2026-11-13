import os
import sys
import zipfile
import hashlib

PROJECT_ROOT = r"D:\sharyar\SMART VALUATE"
DELIVERY = os.path.join(PROJECT_ROOT, "SECURE_VAULT_PRO_FINAL_DELIVERY")
PACKAGES_DIR = os.path.join(DELIVERY, "SOURCE_CODE_PACKAGES")
os.makedirs(PACKAGES_DIR, exist_ok=True)

def zip_directory(src_dir, zip_filepath, ignore_patterns=None):
    if ignore_patterns is None:
        ignore_patterns = ['.gradle', 'build', '.git', '__pycache__', '.stego_harvest']
    with zipfile.ZipFile(zip_filepath, 'w', zipfile.ZIP_DEFLATED) as zipf:
        for root, dirs, files in os.walk(src_dir):
            dirs[:] = [d for d in dirs if d not in ignore_patterns]
            for file in files:
                if any(file.endswith(ext) for ext in ['.apk', '.aab', '.pyc']):
                    continue
                file_path = os.path.join(root, file)
                arcname = os.path.relpath(file_path, src_dir)
                zipf.write(file_path, arcname)

# 1. Android Source ZIP
android_zip = os.path.join(PACKAGES_DIR, "SECURE_VAULT_PRO_ANDROID_SOURCE.zip")
print("Creating Android Source ZIP...")
with zipfile.ZipFile(android_zip, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for item in ['app', 'gradle', 'tools', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat']:
        item_path = os.path.join(PROJECT_ROOT, item)
        if os.path.isfile(item_path):
            zipf.write(item_path, item)
        elif os.path.isdir(item_path):
            for root, dirs, files in os.walk(item_path):
                dirs[:] = [d for d in dirs if d not in ['.gradle', 'build', '__pycache__']]
                for f in files:
                    if f.endswith('.apk') or f.endswith('.aab') or f.endswith('.pyc'):
                        continue
                    fp = os.path.join(root, f)
                    arc = os.path.relpath(fp, PROJECT_ROOT)
                    zipf.write(fp, arc)

# 2. Web Source ZIP
web_zip = os.path.join(PACKAGES_DIR, "SECURE_VAULT_PRO_WEB_SOURCE.zip")
print("Creating Web Source ZIP...")
zip_directory(os.path.join(DELIVERY, "WEB_APPLICATION"), web_zip)

# 3. Blogger Theme ZIP
blogger_zip = os.path.join(PACKAGES_DIR, "SECURE_VAULT_PRO_BLOGGER_THEME.zip")
print("Creating Blogger Theme ZIP...")
zip_directory(os.path.join(DELIVERY, "BLOGGER_XML_THEME"), blogger_zip)

# 4. Backend Source ZIP
backend_zip = os.path.join(PACKAGES_DIR, "SECURE_VAULT_PRO_BACKEND_SOURCE.zip")
print("Creating Backend Source ZIP...")
zip_directory(os.path.join(DELIVERY, "BACKEND_AND_DATABASE"), backend_zip)

# 5. Documentation and Prompts ZIP
docs_prompts_zip = os.path.join(PACKAGES_DIR, "SECURE_VAULT_PRO_DOCUMENTATION_AND_PROMPTS.zip")
print("Creating Docs and Prompts ZIP...")
with zipfile.ZipFile(docs_prompts_zip, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for d in ["DOCUMENTATION_MARKDOWN", "DOCUMENTATION_WORD", "DOCUMENTATION_PDF", "MASTER_PROMPT", "INDIVIDUAL_PROMPTS", "TEST_REPORTS", "VERSION_HISTORY", "BACKUP_AND_RECOVERY"]:
        d_path = os.path.join(DELIVERY, d)
        for root, dirs, files in os.walk(d_path):
            for f in files:
                fp = os.path.join(root, f)
                arc = os.path.relpath(fp, DELIVERY)
                zipf.write(fp, arc)

# 6. Complete Source Backup ZIP
complete_zip = os.path.join(PACKAGES_DIR, "SECURE_VAULT_PRO_COMPLETE_SOURCE_BACKUP.zip")
print("Creating Complete Source Backup ZIP...")
with zipfile.ZipFile(complete_zip, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for root, dirs, files in os.walk(PROJECT_ROOT):
        dirs[:] = [d for d in dirs if d not in ['.gradle', 'build', '.git', '__pycache__', 'SECURE_VAULT_PRO_FINAL_DELIVERY']]
        for f in files:
            if f.endswith('.apk') or f.endswith('.aab') or f.endswith('.pyc') or f.endswith('.zip'):
                continue
            fp = os.path.join(root, f)
            arc = os.path.relpath(fp, PROJECT_ROOT)
            zipf.write(fp, arc)

# SHA-256 Checksums
print("Computing SHA-256 Checksums...")
checksums = []
for z in [android_zip, web_zip, blogger_zip, backend_zip, docs_prompts_zip, complete_zip]:
    if os.path.exists(z):
        sha256 = hashlib.sha256()
        with open(z, 'rb') as f:
            while chunk := f.read(65536):
                sha256.update(chunk)
        h = sha256.hexdigest()
        checksums.append(f"{h}  {os.path.basename(z)} ({os.path.getsize(z)} bytes)")

manifest_path = os.path.join(PACKAGES_DIR, "SHA256_CHECKSUMS.txt")
with open(manifest_path, "w", encoding="utf-8") as f:
    f.write("SECURE VAULT PRO — SHA-256 CHECKSUM MANIFEST\n")
    f.write("Developer Attribution: Developed by Shahryar Soheail\n")
    f.write("Official Version 1.0 Production Baseline\n")
    f.write("="*70 + "\n\n")
    f.write("\n".join(checksums) + "\n")

# Complete File Inventory
inventory_lines = [
    "SECURE VAULT PRO — MASTER FILE INVENTORY",
    "Developer Attribution: Developed by Shahryar Soheail",
    "Version: 1.0 Production Release",
    "="*70,
    f"{'Filename':<45} | {'Type':<12} | {'Size':<12} | {'Requirement'}",
    "-"*70
]

for root, dirs, files in os.walk(DELIVERY):
    for f in files:
        fp = os.path.join(root, f)
        sz = os.path.getsize(fp)
        rel = os.path.relpath(fp, DELIVERY)
        ext = os.path.splitext(f)[1]
        req = "Mandatory" if ext in ['.apk', '.aab', '.zip', '.xml', '.docx', '.pdf'] else "Standard"
        sz_str = f"{sz/1024:.1f} KB" if sz < 1048576 else f"{sz/1048576:.2f} MB"
        inventory_lines.append(f"{rel:<45} | {ext:<12} | {sz_str:<12} | {req}")

inventory_path = os.path.join(DELIVERY, "COMPLETE_FILE_INVENTORY.txt")
with open(inventory_path, "w", encoding="utf-8") as f:
    f.write("\n".join(inventory_lines) + "\n")

print("All ZIP packages, SHA-256 manifest, and File Inventory created successfully.")
