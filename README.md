# KITSUNE

**Offline First Media Library & Reader for Android**

Kitsune is a high-performance, unified media manager designed for local consumption of digital comics and videos on Android. It prioritizes user privacy and data ownership by treating your local storage as the absolute source of truth.

Built with Jetpack Compose • Filesystem First • Hybrid SAF • Automatic Metadata

<p align="left">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-Min%20SDK%2026-3DDC84?style=for-the-badge&logo=android" alt="Android Min SDK">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Database-Room%20v7-8A2BE2?style=for-the-badge&logo=sqlite" alt="Room DB">
  <img src="https://img.shields.io/badge/Status-Phase%2011.4-success?style=for-the-badge" alt="Status">
  <img src="https://img.shields.io/badge/License-MIT-orange?style=for-the-badge" alt="License">
</p>

---

## 📋 Table of Contents
- [Overview](#-overview)
- [Features](#-features)
- [Supported Media](#-supported-media)
- [How It Works](#-how-it-works)
- [Performance](#-performance)
- [Getting Started](#-getting-started)
- [Roadmap](#-roadmap)
- [Current Status](#-current-status)
- [License](#-license)

---

## 📖 Overview
Kitsune changes the game by using a **Filesystem First** approach where metadata follows the media, ensuring your library remains portable, resilient, and lightning-fast. It combines a powerful Manga reader with a robust Video player in a consistent Jetpack Compose interface.

---

## ✨ Why Kitsune?
*   **✔ Offline First:** Zero internet dependency. Your media stays on your device.
*   **✔ Filesystem First:** Filesystem is the Source of Truth. No brittle database silos.
*   **✔ Automatic Metadata:** Smart parsing of folder names to extract Type, Author, and Language.
*   **✔ Visual Polish:** Dimmed covers for bookmarks and consistent ribbon-style icons.
*   **✔ Privacy Focused:** No trackers, no accounts, and no data extraction.

---

## 🛠️ Features

| Feature | Description |
| :--- | :--- |
| **Flexible Parsing** | Detects `[TYPE] [LANG] [AUTHOR] Title` folder patterns automatically. |
| **Fast Reader** | CBZ reader with **Beyond Viewport Loading** and **Coil Speculative Prefetching**. |
| **In-Reader Selector** | Switch between Vertical, LTR, and RTL modes instantly while reading. |
| **Clean Detail UI** | Distraction-free screens without raw folder paths. |
| **Search & Sort** | Multi-field search and advanced sorting by Title, Author, or Date Added. |
| **Video Stability** | Media3 ExoPlayer with MTK Hardware Recovery and external player intents. |
| **Data Integrity** | Unique constraints and anti-duplicate migrations for a clean database. |

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
│   │   └── cover.jpg
│   └── [Manga] [ID] [Oda] One Piece/
│       └── ...
└── Videos/
    └── [Anime] [JP] Makoto Shinkai/
        └── Your Name.mp4
```

---

## ⚡ Performance
Kitsune is tuned for real-world performance on physical devices:
*   **🚀 Coil Prefetching:** Speculatively loads next pages (N+1, N+2) in the background.
*   **🖼️ Anti-OOM Protection:** Aggressive bitmap downsampling and memory management.
*   **🛡️ Navigation Guards:** Prevents backstack bloat via `navigateSafe`.
*   **🧵 Parallel Scanning:** Scans Comics and Videos simultaneously without UI lag.

---

## 🗺️ Roadmap
- [x] **Phase 11: Advanced Metadata & Recovery:** [COMPLETED]
    - Automatic Folder Parsing (`[TYPE] [LANG] [AUTHOR] Title`).
    - Multi-field Search & Advanced Sorting.
    - In-Reader Mode Selector.
    - **Reader Optimization:** Beyond Viewport & Prefetching.
    - **Visual Polish:** Mihon-style dimmed covers & Ribbon icons.
    - **Integritas:** Database v7 with anti-duplicate migration.
- [ ] **Phase 12: Backup & Restore:** Export collection data to the `/Backup` folder.
- [ ] **Phase 13: Themes & Customization:** Custom accent colors and OLED Black.

---

## 📄 License
This project is licensed under the **MIT License**.

---
<p align="center">Developed with ❤️ for the Offline Media Community.</p>
