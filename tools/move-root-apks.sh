#!/usr/bin/env bash
# Moves the loose release APK files out of the project root onto C:. They are historical
# build outputs; D: is 100% full and the rebuild needs room. Nothing is deleted: each file is
# copied, md5-verified against the source, and only then removed.
set -u
SRC="/d/sharyar/SMART VALUATE"
DST="/c/Users/Sharyar/SmartVaultate-APK-Archive"
moved=0; skipped=0; failed=0
cd "$SRC"
for f in SecureWalletPro-*.apk; do
  [ -f "$f" ] || continue
  if cp "$f" "$DST/$f.tmp"; then
    a=$(md5sum "$f" | cut -d' ' -f1)
    b=$(md5sum "$DST/$f.tmp" | cut -d' ' -f1)
    if [ "$a" = "$b" ]; then
      mv "$DST/$f.tmp" "$DST/$f" && rm -f "$f" && moved=$((moved+1))
      echo "moved $f"
    else
      rm -f "$DST/$f.tmp"; echo "MD5 MISMATCH, kept source: $f"; failed=$((failed+1))
    fi
  else
    echo "COPY FAILED, kept source: $f"; failed=$((failed+1))
  fi
done
echo "=== moved=$moved failed=$failed ==="
df -h /d | tail -1
