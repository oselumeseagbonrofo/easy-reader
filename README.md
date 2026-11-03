# Easy Reader

A modern, distraction-free native Android ebook reader built with **Kotlin** and **Jetpack Compose (Material 3)**. Easy Reader supports reading `.epub` and `.pdf` documents with strict page-by-page swiping, live reading progress tracking, inline highlights & passage bookmarks, full-text search, and in-place document referencing.

---

## Features

- **EPUB & PDF Support**:
  - **EPUB ($O(1)$ Random-Access + Lazy Windowed Pagination)**: Parses the ZIP Central Directory over a `SeekableByteChannel` (`EpubRandomAccessArchive`) for $O(1)$ chapter and image lookups, renders the active chapter immediately, and paginates surrounding chapters radially in the background without losing your paragraph anchor.
  - **PDF (Viewport Tile Zoom)**: Hardware-backed rendering via Android's `PdfRenderer` with pinch and double-tap zoom up to 5×, rasterizing only the visible viewport tile at high DPI so memory stays flat.
- **Inline Text Selection, Highlights & Passage Bookmarks**:
  - Long-press and drag in EPUBs (with one-tap **Sentence** and **Paragraph** expand chips) to apply 4-color inline highlights or bookmark specific sentences and paragraphs directly alongside page bookmarks.
- **Strict Page-by-Page Reading**: Horizontal paging (`HorizontalPager`) with left/right tap zones and center-tap HUD toggle.
- **Two-Tier Progress Tracking**:
  - Subtle `3.dp` ambient progress strip and page count always visible at the bottom of the reader screen.
  - Interactive scrubbing slider in the overlay HUD with chapter preview, return-to-spot chip, and page jumping.
  - Real-time bookshelf progress bars and a **Continue Reading** banner.
- **In-Place File Storage**:
  - References external files in place using Android Storage Access Framework (`content://` URIs with persistable permissions).
  - **Zero duplicate copies** stored in internal storage; documents remain safely in your device folders with non-destructive re-linking if moved.
- **In-Book Search**: Full-text search with query debounce, match navigation steppers, and inline/canvas bounding-box highlights.
- **Reading Themes**: Paper, Sepia, Dark, and AMOLED themes, font customization (Serif, Sans, Monospace, font size, line height), and PDF dark-mode color inversion.

---

## Tech Stack & Architecture

- **Language & Framework**: Kotlin 2.2, Jetpack Compose, Material 3
- **Architecture**: Clean Architecture / MVVM with Kotlin Coroutines & Flow
- **Local Persistence**: Room SQLite v2 (bookshelf metadata, page & passage bookmarks, inline highlights with migration `1 -> 2`) & Jetpack DataStore (reader preferences)
- **Document Engines**:
  - Android `PdfRenderer` (with matrix viewport tile zoom) + PDFBox-Android (outlines and search coordinates)
  - Custom $O(1)$ Central Directory EPUB archive reader + Jsoup DOM parser
- **Release Optimization**: R8 code shrinking + resource shrinking enabled in `release` builds

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
2. Build Debug or R8-shrunk Release APKs with Gradle:
   ```bash
   ./gradlew assembleDebug assembleRelease
   ```
3. Install onto a connected device:
   ```bash
   ./gradlew installDebug
   ```

### Running Tests

Execute JVM unit tests and v2 performance benchmarks:
```bash
./gradlew testDebugUnitTest
```

---

## License

This project is licensed under the MIT License.
