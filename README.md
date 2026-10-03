<div align="center">

<img src="bhavyam.png" alt="Bhavyam Logo" width="120" height="120" style="border-radius: 50%;" />

# Bhavyam

**Next-Gen YouTube Music Client with Algorithmic Smart Playlist Generator, Real-Time Synced Lyrics & Universal Last.fm Scrobbler for Android.**

<p align="center">
  <a href="https://github.com/subhamp29/Bhavyam/stargazers">
    <img src="https://img.shields.io/github/stars/subhamp29/Bhavyam?style=for-the-badge&color=ffd0b0&labelColor=2d2d2d" alt="Stars" />
  </a>
  <a href="https://github.com/subhamp29/Bhavyam/network/members">
    <img src="https://img.shields.io/github/forks/subhamp29/Bhavyam?style=for-the-badge&color=ffb4a2&labelColor=2d2d2d" alt="Forks" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Client-YouTube%20Music-FF0000?style=for-the-badge&logo=youtubemusic&logoColor=white&labelColor=2d2d2d" alt="YouTube Music Client" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Scrobbler-Last.fm-D51007?style=for-the-badge&logo=lastdotfm&logoColor=white&labelColor=2d2d2d" alt="Last.fm Scrobbler" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=2d2d2d" alt="Platform" />
  </a>
</p>

</div>

<br/>

<div align="center">
  <img src="Screenshot/screenshot_1.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_2.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_3.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <br/>
  <br/>
  <img src="Screenshot/screenshot_4.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_5.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_6.png" width="31%" style="border-radius: 14px; margin: 4px;" />
</div>

<br/>

## <img src="https://api.iconify.design/lucide:sparkles.svg?color=%23C6F100" width="20" height="20" align="center" /> Overview

**Bhavyam** is a modern native Android music client powered by the global YouTube Music catalog, designed for listeners who want intelligent recommendations, kinetic visuals, and seamless music tracking. 

Built with **Material 3 Expressive**, Bhavyam combines effortless ad-free streaming, an AI-powered smart playlist generator, real-time synchronized karaoke lyrics, and a built-in Last.fm scrobbler that watches your playback across your favorite music apps.

---

## <img src="https://api.iconify.design/lucide:layers.svg?color=%23C6F100" width="20" height="20" align="center" /> Key Features

| Icon | Feature | Highlight |
|:---:|:---|:---|
| <img src="https://api.iconify.design/lucide:play-circle.svg?color=%23FF0000" width="20" height="20" /> | **YouTube Music Client** | Stream tracks, albums, artists, and public playlists from YouTube Music with zero ads and background playback. |
| <img src="https://api.iconify.design/lucide:wand-2.svg?color=%23C6F100" width="20" height="20" /> | **Smart Playlist Generator** | Algorithmic taste mixes and mood radios generated from your listening history, seed artists, loved tracks, and top genres. |
| <img src="https://api.iconify.design/lucide:radio.svg?color=%23D51007" width="20" height="20" /> | **Universal Last.fm Scrobbler** | Built-in media scrobbler tracking listening activity across YouTube Music, Spotify, Apple Music, and local players with zero battery drain. |
| <img src="https://api.iconify.design/lucide:mic.svg?color=%23FFB4A2" width="20" height="20" /> | **Real-Time Synced Lyrics** | Millisecond-accurate animated karaoke lyrics powered by LRCLIB with 8 customizable fluid physics motions. |
| <img src="https://api.iconify.design/lucide:arrow-down-to-line.svg?color=%23C6F100" width="20" height="20" /> | **Offline Downloader** | One-tap downloads saved directly to local storage (`Music/Bhavyam`), fully tagged with high-res cover art and synchronized `.lrc` lyrics. |
| <img src="https://api.iconify.design/lucide:compass.svg?color=%2300E5FF" width="20" height="20" /> | **Discovery Feed & Genre DNA** | Personalized recommendation radar with deep genre breakdowns, weekly listening recaps, and instant "Start Mix" radios. |
| <img src="https://api.iconify.design/lucide:share-2.svg?color=%23C6F100" width="20" height="20" /> | **Cross-Platform Playlist Import** | Instantly import public playlists from Spotify and Apple Music directly into your Bhavyam library. |
| <img src="https://api.iconify.design/lucide:users.svg?color=%23FFB4A2" width="20" height="20" /> | **Social Feed & Friends** | Follow friends' listening activity via Last.fm, browse their recent scrobbles, and explore their top tracks. |
| <img src="https://api.iconify.design/lucide:palette.svg?color=%23C6F100" width="20" height="20" /> | **Material 3 Expressive** | Dynamic wallpaper theming, custom HSL color palette engine, album art color extraction, fluid card animations, and tactile haptics. |

---

## <img src="https://api.iconify.design/lucide:cpu.svg?color=%23C6F100" width="20" height="20" align="center" /> Tech Stack & Architecture

- **Language & UI:** 100% Kotlin + Jetpack Compose (Material 3 Expressive)
- **Audio Engine:** AndroidX Media3 ExoPlayer with lockscreen media controls & Android Auto integration
- **Streaming Catalog:** YouTube Music streaming engine with high-efficiency Opus audio
- **Scrobbling & Tracking:** Last.fm API with native OS media session tracking
- **Lyrics Engine:** [LRCLIB](https://lrclib.net) millisecond-synchronized lyrics
- **Architecture:** MVVM + Clean Architecture, Room DB, Jetpack DataStore, Dagger Hilt, Coroutines & Flow

---

## <img src="https://api.iconify.design/lucide:rocket.svg?color=%23C6F100" width="20" height="20" align="center" /> Getting Started

1. Download the latest APK from the **[Releases](https://github.com/subhamp29/Bhavyam/releases)** or **[Actions](https://github.com/subhamp29/Bhavyam/actions)** tab.
2. Install `Bhavyam-v4.2.2-release.apk` on your Android device (Android 7.0+).
3. Connect your Last.fm account in **Settings → Integrations** to unlock the scrobbler, taste mixes, and personalized discovery radar.
4. Enjoy ad-free streaming and smart playlist generation!

---

## <img src="https://api.iconify.design/lucide:terminal.svg?color=%23C6F100" width="20" height="20" align="center" /> Building from Source

```bash
git clone https://github.com/subhamp29/Bhavyam.git
cd Bhavyam
./gradlew assembleRelease
```

---

## <img src="https://api.iconify.design/lucide:globe.svg?color=%2324A1DE" width="20" height="20" align="center" /> Project Links

* <img src="https://api.iconify.design/lucide:globe.svg?color=%2324A1DE" width="16" height="16" align="center" /> **Website:** [visit site now](https://Bhavyam.pages.dev)
* <img src="https://api.iconify.design/lucide:message-circle.svg?color=%2324A1DE" width="16" height="16" align="center" /> **GitHub Discussions:** [open a discussion](https://github.com/subhamp29/Bhavyam/discussions)

---

## <img src="https://api.iconify.design/lucide:shield-alert.svg?color=%23FFB4A2" width="20" height="20" align="center" /> Disclaimer

> [!NOTE]
> **Educational & Research Notice**
>
> Bhavyam is an open-source, non-commercial application developed strictly for research and educational purposes to demonstrate modern Android application architecture with Jetpack Compose and AndroidX Media3.
>
> **No Affiliation & Content Policy**
> - Bhavyam is an independent project and is **not affiliated with, endorsed, or sponsored by Google LLC, YouTube, YouTube Music, Spotify, Apple Inc., Last.fm, or any music service**.
> - Bhavyam **does not host, stream from private servers, or distribute any copyrighted media**. All content and metadata are accessed directly from public endpoints in accordance with their respective terms. All trademarks belong to their respective owners.

---

[![GitGem](https://gitgem.org/api/badge/github/subhamp29/Bhavyam.svg)](https://gitgem.org/github/subhamp29/Bhavyam)
