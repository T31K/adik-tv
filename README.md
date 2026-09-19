# ADIK TV

A private, family-friendly media hub for Android TV. Movies and shows are
downloaded to a USB drive over BitTorrent and played **fully locally** — no
streaming accounts, no debrid, no cloud.

Built as a heavily trimmed fork of [ARVIO](https://github.com/ProdigyV21/ARVIO)
(Kotlin + Jetpack Compose). Credit to the ARVIO project for the foundation.

## How it works

1. A curated catalog is served by the family backend (`/megaflix` routes on
   main-server). The app mirrors it as the Movies / TV rows.
2. Picking a title downloads it via **libtorrent4j** onto the USB drive
   (FAT32-safe file names, sequential download).
3. Playback is local through Media3/ExoPlayer with a Netflix-style TV player
   (back + centered title, rewind/play/forward cluster, Episodes / Subtitles /
   Next episode row).
4. Fixed profiles (no sign-up): each family member gets their own watch state.
   Switch from the "Who's watching?" screen or the avatar dropdown on Home.
5. Updates ship as GitHub releases; Settings → App update installs them
   in-app (no adb needed).

## Project layout

| Path | What |
|---|---|
| `app/src/main/kotlin/com/arflix/tv/` | Android TV app (package name kept from the fork; applicationId is `com.adik.tv`) |
| `app/src/main/kotlin/com/arflix/tv/megaflix/` | ADIK-specific feed sync + local library |
| `docs/adik-tv-status.md` | Running status / roadmap notes |
| `readme_backup.md` | The original ARVIO readme |

## Building

Requires JDK 17 and the Android command-line tools:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# quick compile check
./gradlew :app:compileSideloadDebugKotlin

# release APK (signed via keystore.properties)
./gradlew :app:assembleSideloadRelease
# → app/build/outputs/apk/sideload/release/app-sideload-release.apk
```

## Releasing

Bump `versionCode` / `versionName` in `app/build.gradle.kts`, build, then:

```bash
cp app/build/outputs/apk/sideload/release/app-sideload-release.apk adiktv_X.Y.Z.apk
cp app/build/outputs/apk/sideload/release/app-sideload-release.apk adik-tv.apk
gh release create vX.Y.Z --repo T31K/adik-tv \
  --title "ADIK TV vX.Y.Z" --notes "..." adiktv_X.Y.Z.apk adik-tv.apk
```

Both asset names matter: the in-app updater picks the newest release's APK
asset, and `adik-tv.apk` is the stable-name download link.

### Pushing to a TV over adb

```bash
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/sideload/release/app-sideload-release.apk
```

## Fork status

The ARVIO base carried many features ADIK doesn't use (Telegram/TDLib
sourcing, IPTV/live TV, Trakt/Simkl sync, cloud accounts, a web app…).
These are being removed in slices — Telegram and the ARVIO web/infra
directories are already gone. See `docs/adik-tv-status.md` for progress.
