# EL MIEDO - Android Flipbook App

A standalone Android app that displays the "EL MIEDO" book as a full-screen flipbook with smooth page-turn animations.

## Features

- **Full-screen landscape mode** - No status bar, no navigation chrome
- **Book page-flip animation** - Realistic 3D rotation effect when swiping pages
- **Screenshot protection** - FLAG_SECURE blocks screenshots and screen recordings
- **Immersive mode** - System bars hidden with swipe-to-reveal
- **Auto-hiding page indicator** - Shows current page briefly, then fades
- **Optimized rendering** - LRU bitmap cache for smooth page transitions

## How to Replace the PDF

1. Place your new PDF file in: `app/src/main/assets/`
2. Rename it to `book.pdf` (or delete the old one and rename yours)
3. Rebuild the app:
   ```bash
   ./gradlew assembleRelease
   ```
4. The new APK will be at: `app/build/outputs/apk/release/app-release.apk`

## Building

### Requirements
- Android Studio (Arctic Fox or newer) OR command-line with Android SDK
- JDK 17
- Android SDK with Build Tools 34

### Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build (signed)
./gradlew assembleRelease
```

### APK Locations
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Project Structure

```
ElMiedo/
├── app/src/main/
│   ├── assets/book.pdf          ← Your PDF goes here
│   ├── java/com/elmiedo/app/
│   │   ├── MainActivity.kt      ← Main activity with PDF renderer
│   │   ├── PageAdapter.kt       ← RecyclerView adapter for pages
│   │   └── BookFlipTransformer.kt ← Page flip animation
│   └── res/
│       ├── layout/               ← Activity and page layouts
│       ├── mipmap-*/             ← App icons (all densities)
│       └── values/               ← Theme and strings
├── release-keystore.jks          ← Signing key
└── build.gradle                  ← Build configuration
```

## Keystore Info

The release keystore is included for convenience:
- **File**: `release-keystore.jks`
- **Alias**: `elmiedo`
- **Passwords**: `ElMiedo2024!`

## Technical Details

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **PDF Rendering**: Android PdfRenderer API (no external libraries)
- **Animation**: Custom ViewPager2 PageTransformer with 3D rotation
- **Security**: WindowManager.FLAG_SECURE applied before content view
