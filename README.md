# Easy Reader

A modern, distraction-free native Android ebook reader built with **Kotlin** and **Jetpack Compose (Material 3)**. Easy Reader supports reading `.epub` and `.pdf` documents with strict page-by-page swiping, live reading progress tracking, full-text search, and in-place document referencing.

---

## Features

- **EPUB & PDF Support**:
  - **EPUB**: Custom streaming parser leveraging `ZipInputStream` and `Jsoup`, rendering rich HTML typography (headings, semantic formatting, blockquotes, lists, images) with line-accurate Compose pagination.
  - **PDF**: Hardware-backed rendering using Android's `PdfRenderer` with double-tap and pinch-to-zoom support up to 4×.
- **Strict Page-by-Page Reading**: Horizontal paging (`HorizontalPager`) with configurable tap navigation zones (left/right margins) and center-tap HUD toggle.
- **Two-Tier Progress Tracking**:
  - A subtle `3.dp` ambient progress bar and page count caption always visible at the bottom of the reader screen.
  - Interactive scrubbing slider in the overlay HUD with chapter preview and page jumping.
  - Real-time bookshelf progress bars and a **Continue Reading** banner.
- **In-Place File Storage**:
  - References external files in place using Android Storage Access Framework (`content://` URIs with persistable permissions).
  - **Zero duplicate copies** stored in internal storage; documents remain safely in your device folders.
  - Graceful file re-linking if external files are moved.
- **In-Book Search**: Full-text search with instant query debounce, match navigation steppers, and inline/canvas bounding-box highlights.
- **Table of Contents & Bookmarks**: Fast chapter jumping and custom page bookmarking with text snippets.
- **Reading Themes**: Paper, Sepia, Dark, and AMOLED themes, plus font customization (Serif, Sans, Monospace, font size, and line height) and PDF dark-mode color inversion.

---

## Tech Stack & Architecture

- **Language & Framework**: Kotlin 2.2, Jetpack Compose, Material 3
- **Architecture**: Clean Architecture / MVVM with Kotlin Coroutines & Flow
- **Local Persistence**: Room SQLite (bookshelf metadata, bookmarks) & Jetpack DataStore (reader preferences)
- **Document Engines**:
  - Android `PdfRenderer` + PDFBox-Android (PDF outlines and search metadata)
  - Custom streaming EPUB parser with Jsoup
- **Image Loading**: Coil (Coil 2.7)

---

## Getting Started

### Prerequisites

- Android Studio Ladybug / Meerkat or later
- Android SDK 36 (Android 15+)
- JDK 17 or 21 (bundled JBR recommended)
- Android device running Android 8.0 (API 26) or higher

### Build & Run

1. Clone this repository:
   ```bash
   git clone https://github.com/oselumeseagbonrofo/easy-reader.git
   cd easy-reader
   ```
2. Open the project in Android Studio, or build directly with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install onto a connected device:
   ```bash
   ./gradlew installDebug
   ```

### Running Tests

Execute JVM unit tests for parsers, search engines, and progress calculation:
```bash
./gradlew testDebugUnitTest
```

---

## License

This project is licensed under the MIT License.
