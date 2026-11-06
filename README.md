# Margin

A personal PDF reader and S Pen note app for an Android tablet (Galaxy Tab, Android 10+).

**Status:** reader + S Pen writing + saving into the PDF.

- Read: continuous pages, fling, pinch / double-tap zoom, pages sidebar, remembers your page and zoom.
- Write: pen tray (ballpoint, fountain pen, pencil, marker, highlighter), area and stroke eraser, undo/redo, S Pen side button erases, palm rejection, zoom lock (two-finger scrolling).
- Save: your ink is written into the original PDF as standard ink annotations (so other apps show it) when you leave the PDF, when the app goes to the background, and after 20 s without new strokes. Before the first save Margin keeps a backup of the untouched original; long-press a PDF in the library to restore it.

## Install on the tablet

1. On the tablet, open this repo's **Releases** page and download the newest `margin-build-N.apk`.
2. Tap the download. The first time, Android asks to **allow Chrome to install apps**: allow it, go back, tap Install.
3. After that, the library shows a banner when a new build is out. Tap **Download**: Chrome downloads it, and you tap the download to install. You can also check from **Settings › Check for updates**. The app itself only asks for internet access and never installs anything on its own.

## One-time setup: signing key (GitHub Secrets)

Android only installs an update over the app when it's signed with the same key, so every build is signed with one key kept in GitHub Secrets. Without the secrets, builds still run but use a throwaway key and nothing is published.

In the repo on GitHub: **Settings › Secrets and variables › Actions › New repository secret**, add:

| Name | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the long base64 text of the keystore file |
| `SIGNING_PASSWORD` | the keystore password |

Keep a backup of the keystore file and password somewhere safe. If they're lost, the app must be uninstalled once before a build with a new key will install, and uninstalling deletes the app's saved data.

## How it's built

- Kotlin + Jetpack Compose, one activity.
- [MuPDF](https://mupdf.com) renders pages (AGPL; this is a personal, open-source app).
- `app/src/main/java/app/margin/`
  - `pdf/PdfDoc.kt` opens and renders PDFs on a single MuPDF thread.
  - `ui/ViewerState.kt` holds the page layout, scroll and zoom math (unit-tested).
  - `ui/DocumentViewer.kt` handles gestures and page rendering, with sharp re-renders after zooming.
  - `ui/LibraryScreen.kt` and `ui/ReaderScreen.kt` are the two screens.
  - `data/Library.kt` keeps the list of recent PDFs (a small JSON file).
  - `update/Updater.kt` provides the in-app update from GitHub Releases.
- `.github/workflows/build.yml`: every push runs the tests, builds a signed APK, and publishes it as release `build-<run number>`.

Build locally: `./gradlew assembleDebug` (needs JDK 17+ and the Android SDK).
