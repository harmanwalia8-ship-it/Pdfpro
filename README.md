# Margin

A personal PDF reader and S Pen note app for an Android tablet (Galaxy Tab, Android 10+).

**Status:** reader + S Pen writing + notebooks + page tools. Private by design: no internet access, and Margin never sends your notes anywhere.

- Read: continuous pages, fling, pinch / double-tap zoom, remembers your page and zoom.
- Find: **search** inside a PDF (🔍 in the reader: matches are marked on the pages, with previous / next), the PDF's own **table of contents**, your own **bookmarks** (🔖 marks the page you're on), and a **"With notes"** filter that shows only pages you wrote on. All in the side panel: Pages · Contents · Bookmarks.
- Write: pen tray (ballpoint, fountain pen, pencil, marker, highlighter), area and stroke eraser, undo/redo, S Pen side button erases, palm rejection, zoom lock (two-finger scrolling).
- Highlight: a highlighter swipe over printed text snaps to clean highlights over the lines it covers (switch it off in the highlighter's options).
- Text and pictures: the **T** tool adds typed text boxes (tap the page; tap a box again to edit it); the picture button adds an image from your photos. Tap a text box or picture to select it, drag to move, drag its corner to resize; Edit, Color, Duplicate and Delete sit above it. Pictures are copied into Margin.
- Keep: Margin keeps its own copy of every PDF you open, so it stays in the library even if you delete or move the original. Until you write in a PDF, Margin refreshes its copy from the original each time you open it.
- Save: your ink is written into Margin's copy of the PDF as standard ink annotations (so other apps show it in a PDF you share or save a copy of). Margin only ever reads your original files and never changes them. Saving happens when you leave the PDF, when the app goes to the background, and after 20 s without new strokes. Before the first save Margin keeps a backup of the untouched PDF; long-press a PDF in the library to restore it.
- New notes: start a notebook from the library: portrait or landscape A4, on blank, lined, grid, dotted, Cornell, music or planner paper. When you write on its last page, a fresh page with the same paper is added after it.
- Pages: in the side panel, hold a page and drag it to a new place; hold it and let go for its menu: add a page after it (any paper), **duplicate** it, **turn** it clockwise, or delete it. Your notes and bookmarks move with their pages.
- Page tools (✂ in the reader, or long-press in the library): **keep pages**, e.g. pages 1–200 as a new PDF, or **split** into PDFs of N pages each. The new PDFs appear in the library; the PDF they came from isn't changed.
- Library: **folders** (long-press a PDF › Move to folder; hold a folder to rename or remove it), sorting by recently opened, name or size, and **Rename**.
- **Merge** (long-press › Merge with other PDFs): tap PDFs in the order they should go in; Margin joins them into a new PDF with your notes, and a contents entry where each one starts.
- Long-press a PDF in the library for: **Save a copy to Files**, to put a PDF made in Margin (or any PDF, with your notes) into Files or Drive, and **Delete from Margin**, which deletes Margin's copy, your notes and the backup for good. The original file is never touched.

## Privacy

Margin itself never sends your PDFs, notes or ink anywhere. They only leave the tablet when you choose **Share** or **Save a copy to Files**, or through Android's own backup and new-tablet transfer.

- **No internet access.** The app doesn't have Android's internet permission, so Android itself blocks it from sending anything. Checking for updates just opens the Releases page in Chrome.
- **Android backup and new-tablet transfer are on.** When you set up a new tablet from the old one, your whole library comes along. Android's Google backup is encrypted with your screen lock, but Google takes at most 25 MB per app and skips the app entirely above that. The untouched-original copies behind "Restore original PDF" are left out to save room, but a library of big PDFs will still go over. For that, rely on the new-tablet transfer, or **Save a copy to Files**.
- **Your original files are never changed.** Margin only reads them. Your ink goes into Margin's own copy, so a PDF in Google Drive or OneDrive never gets your notes uploaded.
- **Checked on every build.** `PrivacyTest` fails the build if internet access, network code or new writes outside the app appear, or if the backup settings change. CI also checks the finished APK has no internet permission. The emulator test checks that saving leaves the original file byte-for-byte unchanged.

## Install on the tablet

1. On the tablet, open this repo's **Releases** page and download the newest `margin-build-N.apk`.
2. Tap the download. The first time, Android asks to **allow Chrome to install apps**: allow it, go back, tap Install.
3. To update later: **Settings › Check for updates** opens the newest release in Chrome. If its build number is higher than yours (shown in Settings), download the APK and tap it to install. Margin itself has no internet access and never installs anything on its own.

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
  - `pdf/PdfStore.kt` keeps Margin's copy of each PDF (`files/docs/`) in step with the original: open, save, backup and restore, export, delete.
  - `pdf/PageTools.kt` makes new PDFs: blank notebooks, a kept page range, a split into parts, or a merge.
  - `pdf/PageMoves.kt` is how page numbers shift when pages are added, deleted, moved or turned (unit-tested).
  - `ink/InkController.kt` holds your notes on each page (ink, text boxes, pictures), with undo; `ink/PageItems.kt` has the text box / picture model and the highlighter snapping (unit-tested).
  - `ui/SidePanel.kt` (pages, contents, bookmarks), `ui/Search.kt`, and the dialogs in `ui/*Dialog*.kt`.
  - `ui/ViewerState.kt` holds the page layout, scroll and zoom math (unit-tested).
  - `ui/DocumentViewer.kt` handles gestures and page rendering, with sharp re-renders after zooming.
  - `ui/LibraryScreen.kt` and `ui/ReaderScreen.kt` are the two screens.
  - `data/Library.kt` keeps the list of PDFs in the library (a small JSON file).
  - `update/Updater.kt` opens the GitHub Releases page for updates (the app has no internet access).
- `.github/workflows/build.yml`: every push runs the tests, builds a signed APK, and publishes it as release `build-<run number>`.

Build locally: `./gradlew assembleDebug` (needs JDK 17+ and the Android SDK).
