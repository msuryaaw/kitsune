# KITSUNE

**Offline First Media Library & Reader for Android**

Kitsune is a high-performance, unified media manager designed for local consumption of digital comics and videos on Android. It prioritizes user privacy and data ownership by treating your local storage as the absolute source of truth.

Built with Jetpack Compose • Filesystem First • Hybrid SAF • Automatic Metadata • Single-Query SQL JOINs

<p align="left">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-Min%20SDK%2026-3DDC84?style=for-the-badge&logo=android" alt="Android Min SDK">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Database-Room%20v10-8A2BE2?style=for-the-badge&logo=sqlite" alt="Room DB v10">
  <img src="https://img.shields.io/badge/Status-v1.0.0%20MVP%20%5BSTABLE%5D-success?style=for-the-badge" alt="Status">
  <img src="https://img.shields.io/badge/License-MIT-orange?style=for-the-badge" alt="License">
</p>

---

## 📋 Table of Contents
- [Overview](#-overview)
- [Features](#-features)
- [Supported Media](#-supported-media)
- [Architecture & Design Principles](#-architecture--design-principles)
- [How It Works](#-how-it-works)
- [Performance & Optimizations](#-performance--optimizations)
- [Testing & Quality Assurance](#-testing--quality-assurance)
- [Roadmap](#-roadmap)
- [License](#-license)

---

## 📖 Overview
Kitsune changes the game by using a **Filesystem First** approach where metadata follows the media (`metadata.json`), ensuring your library remains portable, resilient, and lightning-fast. It combines a powerful CBZ Manga reader with a robust Video player in a consistent Jetpack Compose interface.

---

## ✨ Why Kitsune?
*   **✔ Offline First:** Zero internet dependency. No trackers, no accounts, 100% offline local storage.
*   **✔ Filesystem First:** Filesystem is the Source of Truth (`metadata.json`). No brittle database silos.
*   **✔ Relative Path Identity:** Media is identified using relative paths (`Comics/Title`), making libraries completely portable.
*   **✔ Automatic Metadata:** Smart parsing of folder names to extract Type, Language, Author, and Title.
*   **✔ Visual Polish:** Mihon-style dimmed covers for bookmarked items and standardized ribbon icons.
*   **✔ Clean Architecture:** Pure Kotlin, Jetpack Compose, MVVM, Room v10, and Manual Dependency Injection.

---

## 🛠️ Features

| Feature | Description |
| :--- | :--- |
| **Flexible Parsing** | Detects `[TYPE] [LANG] [AUTHOR] Title` folder patterns automatically. |
| **Thread-Safe CBZ Reader** | Dual-Strategy Parser (Proc-FD + Temp Cache) with isolated byte-decoupled streams for race-free reading. |
| **GPU Texture Downsampling** | Automatic `inSampleSize` downsampling for ultra-high-resolution Webtoon panels (> 8192px) to prevent OOM. |
| **In-Reader Selector** | Switch between Vertical, LTR, and RTL modes instantly while reading. |
| **Read & Watch History** | Detailed activity tracking with Continue Reading/Watching cards and Settings clear options. |
| **Search & Sort** | Multi-token AND search (comma-separated) and advanced sorting by Title, Author, or Date Added. |
| **Video Stability** | Media3 ExoPlayer with MediaTek Hardware Decoder Recovery and Automatic Software Fallback. |
| **Unified Collections** | Mixed-media Bookmarks (Comics & Videos) and Video-only Playlists. |
| **Secure Physical Delete** | Permanently delete media from device storage with strict SAF path validation and DB cleanup. |

---

## 📂 Supported Media

| Type | Formats |
| :--- | :--- |
| **Comic** | `.cbz`, Folder-based images (`jpg`, `jpeg`, `png`, `webp`) |
| **Video** | `mp4`, `mkv`, `mov`, `avi`, `webm`, `m4v`, `ts`, `3gp` |

---

## 📁 Media Directory Structure

The application automatically parses your folder structure to extract rich metadata:

```text
Library-Root/
├── Comics/
│   ├── [Manhwa] [EN] [Chugong] Solo Leveling/ # Type, Lang, Author, Title parsed
│   │   ├── Chapter 01.cbz
│   │   ├── metadata.json
│   │   └── cover.jpg
│   └── [Manga] [ID] [Oda] One Piece/
│       └── ...
└── Videos/
    └── [Anime] [JP] Makoto Shinkai/
        └── Your Name.mp4
```

---

## ⚡ Performance & Optimizations

*   **🚀 Zero N+1 Queries:** Read History, Watch History, and Collection Counts use single-query SQL `INNER JOIN` / `LEFT JOIN` queries.
*   **⚡ Single-Pass SAF Traversals:** `ComicScanner` and `VideoScanner` fetch directory files once per folder, cutting SAF Binder IPC calls by 66%.
*   **🔒 Shared URI Cache:** Single Application-scoped `StorageHelper` instance with 512 LRU cache and automatic memory trimming on low RAM events.
*   **🚀 Quiet on Startup:** Zero filesystem I/O at launch; library data loads instantly from Room database cache.
*   **🖼️ Coil Prefetching & Downsampling:** Speculatively loads next pages (N+1, N+2) in the background with native GPU texture downsampling.
*   **🛡️ Navigation Guards:** Prevents backstack bloat and rapid-click duplication via `navigateSafe`.

---

## 🧪 Testing & Quality Assurance

Kitsune features a behavioral unit test suite targeting core Kotlin utilities and metadata serialization:
*   **Package Alignment:** Test suite aligned to `com.kitsune.app`.
*   **`NaturalOrderComparatorTest`:** Verifies numeric natural sorting (`Chapter 1 < Chapter 2 < Chapter 10`, `image_1.jpg < image_10.jpg`).
*   **`SearchUtilsTest`:** Verifies multi-token comma-separated AND search logic, case insensitivity, and whitespace trimming.
*   **`MediaMetadataTest`:** Verifies JSON serialization, deserialization, and forward compatibility with legacy metadata files.

---

## 🗺️ Roadmap
- [x] **Phase 11: Advanced Features & Recovery:** [COMPLETED]
    - Automatic Folder Parsing (`[TYPE] [LANG] [AUTHOR] Title`).
    - Multi-field Search & Advanced Sorting.
    - In-Reader Mode Selector.
    - **Reader Resilience:** Thread-safe Decoupled Byte Buffer (`CRIT-01`) & Actual Downsampling (`CRIT-02`).
    - **Storage Optimization:** Single Application-scoped `StorageHelper` (`CRIT-03`).
    - **Scanner Optimization:** Single-pass SAF Traversals (`HIGH-01`).
    - **Database Optimization:** Room v10 with junction table indices and single-query SQL JOINs (`HIGH-02`, `HIGH-03`, `HIGH-04`, `HIGH-05`).
    - **Testing Infrastructure:** `com.kitsune.app` unit test suite (`CRIT-TEST-01`, `HIGH-TEST-02`).
- [ ] **Phase 12: Backup & Restore:** Export collection data to the `/Backup` folder.
- [ ] **Phase 13: Themes & Customization:** Custom accent colors and OLED Black.

---

## 📄 License
This project is licensed under the **MIT License**.

---
<p align="center">Developed with ❤️ for the Offline Media Community.</p>
