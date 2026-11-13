# SECURE VAULT PRO — Android Build & Release Guide
**Developer: Developed by Shahryar Soheail**

## Prerequisites
- Android Studio Ladybug / AntiGravity IDE
- JDK 17 or JDK 21
- Android SDK 35 (compileSdk 35, minSdk 26, targetSdk 35)

## Release Generation Commands
```powershell
# Clean build
./gradlew clean

# Build Signed Release APK
./gradlew assembleRelease

# Build Signed Android App Bundle (AAB)
./gradlew bundleRelease
```

## Verification
```powershell
# Verify APK signature
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```
