# Kitsune - Offline Media Library (Manga & Video)

Welcome to the official documentation for **Kitsune**, an offline-first manga, comic, and video library application for Android.

## 1. Project Overview
Kitsune is a high-performance, unified media manager designed for local consumption of digital comics and videos. It emphasizes privacy, speed, and a consistent user experience.

- **Offline First:** No trackers, no accounts, no internet required.
- **Filesystem First:** Your folders are the source of truth.
- **Unified Media Foundation:** A common architectural base for both Comic and Video engines.
- **Privacy Focused:** Uses Android's Storage Access Framework (SAF) for secure file access.

## 2. Current Status
- **Current Phase:** 8.3.5 (Advanced UX & Library Management)
- **Status:** Stable Candidate
- **Milestone:** Phase 8.3 (Advanced Video UX & Management) successfully implemented.
- **Next Goal:** Phase 9 - Backup & Restore System.

## 3. Core Features

### 📖 Comic Engine
- **Library & Detail:** Interactive grid and detail views with natural sorting.
- **Manga Reader:** Supports Vertical, LTR, and RTL modes with seamless chapter transitions.
- **Progress Tracking:** Saves reading page and chapter, supporting "Continue Reading".
- **Auto-Cover:** Automatically generates covers from chapter content if missing.

### 🎬 Video Engine
- **Video Library:** Grid-based browsing with **Selection Mode** and **Bulk Select** support.
- **High-Performance Player:** Powered by Media3 ExoPlayer with advanced gesture controls.
- **Advanced Gestures:** Horizontal Seek, Vertical Brightness (left), and Volume (right) with non-conflicting UI.
- **Playback Management:** Resume Playback, Manual **Orientation Toggle** (Portrait/Landscape), and "Finished" badges.
- **Video Statistics:** Total watch time, completed videos, and usage tracking.

### 📂 Unified Media Foundation
- **Unified Components:** Consistent UI using `MediaGrid`, `MediaLibraryScaffold`, and `MediaCardContainer`.
- **Unified Collections:** Shared Bookmark and Playlist systems for all media types via `CollectionRepository`.
- **Reactive Home:** "Jump Back In" feature for recent activity (Comics & Videos) with "Continue Reading" and "Continue Watching" cards.

## 4. Architecture Summary
Built with modern Android development standards:
- **Clean MVVM:** Decoupled UI, Business Logic, and Data.
- **Repository Pattern:** Centralized data access with a unified facade for collections.
- **Reactive UI:** Driven by Kotlin StateFlow and Room's reactive queries.
- **Hybrid SAF:** `DocumentFile` for navigation and `ContentResolver` for high-performance streaming.
- **Relative Path Identification:** Ensures library portability (data remains valid if root folder is moved).
- **Natural Sorting:** Mandatory alphanumeric sorting applied across all media lists.

## 5. Project Structure
- `com.kitsune.app.core`: Core utilities (StorageHelper, NaturalSort).
- `com.kitsune.app.data.repository`: Data orchestrators (CollectionRepository, ScannerRepository).
- `com.kitsune.app.database`: Room DB definition, entities (VideoProgress, ReadingProgress), and DAOs.
- `com.kitsune.app.domain.model`: Business models (Comic, Video, Episode, Page).
- `com.kitsune.app.navigation`: `KitsuneNavGraph` and URL-encoded route management.
- `com.kitsune.app.reader`: CBZ parsing engine and image handling.
- `com.kitsune.app.scanner`: Specialized engines (ComicScanner, VideoScanner).
- `com.kitsune.app.ui`: Compose-based screens and ViewModels.
    - `components.media`: Unified UI Foundation components.
    - `library.base`: Base ViewModels and common library logic.

## 6. Technology Stack
- **Kotlin & Jetpack Compose**
- **Room Database** (Metadata & Progress)
- **Media3 ExoPlayer** (Video Playback)
- **Coil** (Image Loading & CBZ Fetching)
- **Navigation Compose**

## 7. Documentation Index
- [AI Agent Context](docs/AI_AGENT_CONTEXT.md) - **Primary Entry Point for AI Agents.**
- [Architecture](docs/architecture.md) - Technical architecture and data flow.
- [Foundation](docs/foundation.md) - Core philosophy and project rules.
- [Filesystem](docs/filesystem.md) - Storage structure and SAF rules.
- [Database](docs/database.md) - Room schema and migration history.
- [Navigation](docs/navigation.md) - Routing and parameter handling.
- [UI Specification](docs/ui-spec.md) - Visual components and design rules.
- [Scanner Engine](docs/scanner-engine.md) - Incremental scanning logic.
- [Video Engine](docs/video-engine.md) - Video player and engine specifications.
- [Task Roadmap](docs/task-roadmap.md) - Development history and future plans.

## 8. Development Rules
1. **Relative Path First:** IDs in DB must be relative to the root for portability.
2. **Natural Sorting Mandatory:** Every list must use `NaturalOrderComparator`.
3. **Filesystem is Truth:** Do not store media lists in the DB; read them from the filesystem lazily.
4. **Unified UI:** Use components from the `media` foundation for all library-related features.
