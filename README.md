<div align="center">

<img src="https://img.shields.io/badge/Rushtify-Android%20Music%20Player-0B1F4D?style=for-the-badge&logo=android&logoColor=white" alt="Rushtify" />

# Rushtify

### *Your music, your playlists, your listening history.*

> A native Android music player for YouTube Music streaming, smart playlists, synced lyrics, offline downloads, and Last.fm scrobbling.

<br/>

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Version](https://img.shields.io/badge/Version-1.0.0-256D3D?style=for-the-badge)](https://github.com/Vishwajitsingh-rajput-27/rushtify/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)

<br/>

**Created by [Vishwajitsingh-rajput-27](https://github.com/Vishwajitsingh-rajput-27)**

</div>

---

## Overview

**Rushtify** is a native Android music application built with Kotlin and Jetpack Compose. It connects listeners to the YouTube Music catalog while providing a focused player experience with background playback, lock-screen controls, Android Auto support, playlists, lyrics, downloads, and optional account integrations.

The application is designed to be useful immediately. Users can start listening without configuring an account, import public playlists from supported services, save playlists locally, and customize advanced audio and scrobbling features when needed.

## Highlights

| Area | What Rushtify provides |
|:---|:---|
| **Streaming** | Search and play tracks from the YouTube Music catalog with background playback and queue controls. |
| **Playlists** | Create, import, sort, pin, rename, edit, and play local playlists from one place. |
| **Downloads** | Download tracks to the device with metadata, artwork, lyrics, and format information. |
| **Downloads playlist** | A built-in playlist that is created automatically and stays synchronized with completed downloads, deletions, and download-history changes. |
| **Lyrics** | Real-time synchronized lyrics with an animated reading experience powered by LRCLIB. |
| **Imports** | Import public Spotify and Apple Music playlists by pasting their links. |
| **Scrobbling** | Optionally submit listening activity to Last.fm, including activity from supported external players. |
| **Playback** | Media3 playback, media notification controls, lock-screen controls, queue management, and Android Auto integration. |
| **Personalization** | Material 3 theming, dynamic colors, language selection, accent colors, and audio preferences. |

## Screenshots

The repository includes application screenshots in the [`Screenshot/`](Screenshot/) directory.

<div align="center">

| Playlists | Settings |
|:---:|:---:|
| ![Rushtify playlists screen](Screenshot/screenshot_1.png) | ![Rushtify settings screen](Screenshot/screenshot_2.png) |

| Player | Lyrics |
|:---:|:---:|
| ![Rushtify player screen](Screenshot/screenshot_3.png) | ![Rushtify lyrics screen](Screenshot/screenshot_4.png) |

</div>

## Core Features

### Streaming and playback

Rushtify provides a native browsing and playback experience for tracks, albums, artists, and public playlists. The player supports queues, shuffle, repeat, seeking, next and previous controls, background playback, media notifications, and lock-screen controls. Android Auto support is included for compatible vehicles and head units.

### Playlist management

The Playlists screen stores local playlists in the device database. Users can create custom playlists, import external playlists, sort them by name, date, or track count, pin important playlists, change cover art, and edit track order.

The **Downloads** playlist is maintained by the application rather than by manual editing. When a download completes, the track is added to that playlist. When a downloaded track or download-history record is removed, the playlist is updated as well. Existing download records are synchronized when the Playlists screen opens.

### Public playlist import

Paste a public Spotify or Apple Music playlist link into **Playlists → Import**. Rushtify resolves the playlist metadata and matches its tracks to playable YouTube Music results. Short Spotify links are supported when the source service exposes enough public information for matching.

### Lyrics

Rushtify retrieves lyrics through [LRCLIB](https://lrclib.net), supports synchronized and plain lyrics, and presents them in an animated player view. Downloaded tracks can retain their lyric metadata and sidecar `.lrc` files when available.

### Offline downloads

Tracks can be downloaded to the device for local playback. Rushtify stores download metadata in Room, writes audio files under the configured Rushtify music directory, and can preserve artwork, duration, format, bitrate, and lyric information. The Downloads screen provides progress, playback, deletion, and download-history controls.

### Last.fm integration

Users can optionally connect Last.fm to scrobble listening activity. Scrobbling is disabled unless the user enables and configures it. Advanced settings also include audio, notification, data, and playback-related controls.

## Android Architecture

Rushtify follows a Kotlin-first MVVM architecture with repository boundaries and reactive state flows. Room stores local playlists, download records, artwork metadata, and application data. DataStore manages preferences. Hilt provides dependency injection, while Kotlin Coroutines and Flow coordinate background work and UI state.

| Layer | Technologies and responsibilities |
|:---|:---|
| **UI** | Jetpack Compose, Material 3, lifecycle-aware state collection, expressive animated components |
| **Application logic** | ViewModels, repositories, Kotlin Coroutines, Kotlin Flow |
| **Local persistence** | Room database for playlists and downloads; DataStore for preferences |
| **Playback** | AndroidX Media3 / ExoPlayer, media sessions, notifications, and Android Auto |
| **Networking** | Kotlin HTTP clients and service-specific repository integrations |
| **Dependency injection** | Dagger Hilt |
| **Native audio** | C++ audio components through the Android NDK for supported audio processing paths |
| **Build system** | Gradle Kotlin DSL and Android Gradle Plugin |

## Technology Stack

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Material 3](https://img.shields.io/badge/Material%203-6750A4?style=flat-square&logo=materialdesign&logoColor=white)
![Room](https://img.shields.io/badge/Room-AndroidX-3DDC84?style=flat-square&logo=android&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-FF6F00?style=flat-square&logo=android&logoColor=white)
![Hilt](https://img.shields.io/badge/Hilt-DI-4285F4?style=flat-square&logo=android&logoColor=white)
![C++](https://img.shields.io/badge/NDK%20Audio-C%2B%2B-00599C?style=flat-square&logo=cplusplus&logoColor=white)

## Requirements

- Android Studio with the Android SDK installed.
- Android SDK with the project compile and target SDK platforms available.
- JDK compatible with the Gradle and Android Gradle Plugin versions configured by the project.
- An Android device or emulator running **Android 10 / API 29 or newer**.
- Network access for streaming, metadata lookup, lyrics, imports, and optional Last.fm features.

## Getting Started

### Install a release build

Download an APK from the repository's [Releases](https://github.com/Vishwajitsingh-rajput-27/rushtify/releases) page, enable installation from the appropriate source on your Android device, and install the APK. Android may show a confirmation because the APK is installed outside Google Play.

### Build from source

```bash
git clone https://github.com/Vishwajitsingh-rajput-27/rushtify.git
cd rushtify

# On systems where gradlew is executable:
./gradlew assembleDebug

# If the wrapper does not have execute permission:
bash ./gradlew assembleDebug
```

The generated debug APK is normally placed under:

```text
app/build/outputs/apk/debug/
```

To build a release variant, use:

```bash
bash ./gradlew assembleRelease
```

Release signing depends on the signing configuration supplied to the local build environment. Do not commit private signing keys, passwords, API keys, or generated local configuration files.

## Project Structure

```text
rushtify/
├── app/
│   └── src/main/
│       ├── java/com/rushtify/app/
│       │   ├── data/          # Repositories, APIs, Room, downloads, lyrics
│       │   ├── playback/      # Media3 player and playback services
│       │   ├── ui/            # Compose screens and ViewModels
│       │   └── util/          # Shared Android utilities
│       ├── cpp/               # Native audio components
│       └── res/               # Icons, fonts, strings, themes, and resources
├── audio/                     # USB audio support modules
├── Screenshot/                # Project screenshots
├── tools/                     # Build and native-secret helper scripts
└── gradle/                    # Gradle wrapper and version catalog
```

## Configuration and Privacy

Some integrations require credentials or environment-specific values. Use the provided `.env.example` as a reference and keep local secrets outside version control. Review the permissions and service configuration before distributing a build.

Rushtify stores local playlists, download records, preferences, and related metadata on the device. Network-backed features may send search, playlist, lyrics, scrobbling, or authentication requests to their respective services. Users should review the privacy policies and terms of each service they enable.

## Development Notes

The project uses Room migrations for persistent data. When changing database entities, add and test a migration rather than deleting the local database. UI state should remain lifecycle-aware, and long-running download or synchronization work should run outside the main thread.

The Downloads playlist is intentionally synchronized from completed download records. New download-related code should update the download database through `TrackDownloadManager` or call its playlist synchronization method after direct history changes.

Before opening a pull request, run the relevant Gradle checks available in your Android SDK environment and test playback, downloads, playlist editing, and app restart behavior on an Android 10+ device or emulator.

## Contributing

Contributions are welcome. Before making a large change, open an issue describing the problem and proposed approach. Keep pull requests focused, explain user-visible behavior changes, and include screenshots or reproduction steps for UI work.

A typical contribution workflow is:

```bash
git checkout -b feature/your-change
bash ./gradlew assembleDebug
git add -A
git commit -m "Describe the change"
git push origin feature/your-change
```

Then open a pull request against the `main` branch.

## 👨‍💻 Author

<div align="center">

### Vishwajitsingh Rajput

*Full-Stack Developer · Computer Engineer*

Indira College of Engineering and Management, Pune

<br/>

[![GitHub](https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Vishwajitsingh-rajput-27)
[![Instagram](https://img.shields.io/badge/Instagram-E4405F?style=for-the-badge&logo=instagram&logoColor=white)](https://instagram.com/vishwajit._.25)

</div>

---

## Legal and Service Disclaimer

> Rushtify is an open-source, non-commercial Android application. It is not affiliated with, endorsed by, or sponsored by Google, YouTube, YouTube Music, Spotify, Apple, Last.fm, or any other music service. All trademarks belong to their respective owners. Users are responsible for complying with the terms of service and applicable laws for the services and content they access.

## License

See [`LICENSE`](LICENSE) for the applicable license and [`NOTICE.md`](audio/decent-usb-audio-driver/NOTICE.md) for third-party audio module notices.

## References

[1]: https://developer.android.com/ "Android Developers"
[2]: https://developer.android.com/compose "Jetpack Compose documentation"
[3]: https://developer.android.com/media/media3 "AndroidX Media3 documentation"
[4]: https://developer.android.com/training/data-storage/room "Android Room documentation"

---

<div align="center">

**Rushtify** — a focused Android music player created by [Vishwajitsingh-rajput-27](https://github.com/Vishwajitsingh-rajput-27).

</div>
