# Sukoon (سكون)

A home screen for Android TV boxes. It is light, smooth on weak hardware, and
can be customized from the remote. It replaces the stock Google TV launcher on
a 2 GB, 4x Cortex-A53 box (Android TV 10, 1280x720 UI).

"Sukoon" is a working name. It is set in exactly one place, the first lines of
`app/build.gradle.kts` (`brandName`, `brandNameAr`). The label, the strings and
the HTTP user agent are all generated from it. The package id is
`dev.multix.tvhome` and does not change with the name.

## What it does

- **Home**
  - A full-screen wallpaper that drifts slowly (a GPU-only Ken Burns move) and
    crossfades to the next photo on a timer.
  - A large thin clock with the date and a greeting, in English or Arabic.
  - Optional weather from Open-Meteo: type a city in Settings.
- **Dock**
  - Favorite apps along the bottom, or down the side.
  - Four looks: banner cards, a frosted glass pill, bare icons, or a minimal
    text list.
  - Focus scales the tile and lights a glow in the app's own colour.
  - Down, or the All apps tile, opens a grid of every launchable app.
  - Hold OK on any app for: move, add or remove from the dock, hide, app info.
- **Now playing with synced lyrics**
  - Shows the track that is playing (Spotify, YouTube Music, YouTube, any
    player): artwork, title, artist, a progress line and three lyric lines.
  - The lines glide up in time with the song. Lyrics come from LRCLIB and are
    cached on disk.
  - Three styles: a card, a compact pill, or a full-screen takeover.
- **Full-screen lyrics**
  - Opens after a few seconds without input while music plays, from the card's
    Full screen pill, as the takeover style, or as the system screensaver
    ("Lyrics and photos").
  - The background is the song's own colour field: the cover reduced to 8x5
    pixels, stretched by the GPU, darkened to about 35 % and flowing slowly.
    The bass lifts it by up to 6 %.
  - Every song gets a signature from a hash of its title and artist. The hash
    picks the type pairing and a small edge motif: rings breathing out of the
    corner cover, or a thin live spectrum along the bottom edge. Nothing is ever
    drawn behind the words.
  - The lyric stack shows one line above and two below, at 22 % and 10 % and
    smaller. A line change rolls the stack on a spring.
  - The current line lights up word by word, using enhanced LRC word times
    when present, otherwise an estimate from the line length. Arabic sweeps
    right to left.
  - A repeated line counts as the chorus, and the view eases in slightly there.
    Instrumental gaps longer than 4 s show the title, large and dim.
  - When a song starts, its cover appears and melts into the colour field, then
    the title and then the lyrics rise in.
- **Customize**
  - An editor in the style of the iOS lock screen. The home screen shrinks into
    a framed preview.
  - Up/Down picks a part, OK steps through its options, Left/Right changes the
    value. Every change is saved and shown at once; Back leaves.
- **Settings**
  - A side panel opened from the gear or the Menu key.
  - Sections: wallpaper sets with live preview, clock, lyrics, dock, language,
    weather, system options, about.

## Customize options and defaults

| Part | Option | Default | Other values |
|---|---|---|---|
| Clock | Font | Inter Display (English), IBM Plex Sans Arabic (Arabic) | Inter Display Thin, Inter Display Bold, DM Serif Display, Bebas Neue, Space Grotesk, JetBrains Mono, Playfair Display, Outfit, Readex Pro, Noto Kufi Arabic, Cairo |
| | Style | One line | Stacked (hours over minutes), Outline |
| | Size | Large | Small, Medium, Extra large |
| | Position | Top corner | Center, Bottom corner |
| | Color | White | From wallpaper, Sand, Rose, Sky, Mint, Lilac, Amber |
| | Seconds | Off | On |
| | Format | as the system | 12-hour, 24-hour |
| | Digits | Latin 0-9 | Arabic-Indic |
| Date and greeting | Date | On | Off |
| | Calendar | Gregorian | Hijri (Umm al-Qura) |
| | Greeting | On | Off |
| Dock | Style | Cards | Glass pill, Icons, Text list |
| | Icon shape | Squircle | Circle, Rounded square |
| | App names | On | Off |
| | Tile art | TV banner (loud flat banners fall back to the icon) | App icon |
| | Position | Bottom | Side (the text list is always on the side) |
| | Sort | Custom | A to Z, Recent (needs usage access) |
| Now playing | Style | Card | Compact pill, Full screen |
| | Lyrics size | Medium | Small, Large |
| Wallpaper | Set | the set in `default.txt` (Dusk) | every other bundled or user set |
| | Change every | 10 min | Off, 1, 5, 30 min |
| | Motion | On | Off |
| | Dim | 0 % | 10 to 60 % |
| | Blur | Off | 1 to 5 |
| Language and text | Language | System | English, Arabic (full right-to-left) |
| | Font | Inter (English), IBM Plex Sans Arabic (Arabic) | English: Outfit, Space Grotesk. Arabic: Readex Pro, Noto Kufi Arabic, Cairo |
| Looks | Preset | none | Default, Calm, Cinema, Arabic, Minimal |

The looks:
- **Calm**: Dusk, thin clock, glass pill dock.
- **Cinema**: Space, bold stacked clock in the center, circle icons, full-screen
  lyrics.
- **Arabic**: Arabic UI, Plex Arabic clock, Arabic-Indic digits, Hijri date,
  Desert.
- **Minimal**: Gradients with soft blur, Outfit clock, text list dock, compact
  pill.

Opening Customize:
- On the remote: move up to the clock and press OK, or hold OK.
- From Settings: the Customize row.
- From adb:

      adb shell am start -n dev.multix.tvhome/.HomeActivity --ez customize true

  `input keyevent --longpress DPAD_CENTER` on the clock works too. Long press
  is read from the key repeat, not from a timer.

Other adb entries, handy for recording:

    adb shell am start -n dev.multix.tvhome/.HomeActivity --ez lyrics true            # full-screen lyrics (a sample song if nothing plays)
    adb shell am start -n dev.multix.tvhome/.HomeActivity --ez lyrics true --ei sample 2   # sample songs 0 to 3
    adb shell am start -n dev.multix.tvhome/.HomeActivity --ez nextwall true          # crossfade to the next wallpaper now

The sample songs are original words with generated covers, so they are safe to
show on camera.

## Build

Requirements: the Android SDK (platform 36, build-tools 36) and JDK 17 or newer
(Android Studio's bundled JBR works). The Gradle wrapper downloads Gradle 9.1.

    ./gradlew :app:assembleRelease
    cp app/build/outputs/apk/release/app-release.apk dist/sukoon-launcher.apk

**Signing.** Release signing reads `keystore.properties` from this folder.
Neither the keystore nor that file is in git. Format:

    storeFile=keystore/sukoon-release.jks
    storePassword=...
    keyAlias=sukoon
    keyPassword=...

Without that file the release build falls back to the debug key.

**Wallpapers** are bundled at build time from `../wallpapers/<set>/*.jpg`.
- Images are scaled to 1280x720 on the way in.
- `default.txt` names the default set.
- `sets.json` gives the display names and their order. A value is either
  `"Dusk"` or `{"en": "Dusk", "ar": "الغروب"}`.
- If the folder is empty, a generated placeholder set is bundled.
- Users can add their own sets on the box in `/sdcard/Wallpapers/<set>/`. Any
  size works; images are sampled down on decode. These sets show up in
  Settings after "Allow access to photos".

## Install on the box

    adb install -r dist/sukoon-launcher.apk
    adb shell cmd package set-home-activity dev.multix.tvhome/.HomeActivity
    adb shell cmd notification allow_listener dev.multix.tvhome/dev.multix.tvhome.media.NowPlayingListener

Optional grants. Each feature checks at run time and stays hidden without its
grant:

    # Now playing on low-RAM builds (ro.config.low_ram=true), where Android
    # ignores notification listener grants:
    adb shell pm grant dev.multix.tvhome android.permission.DUMP
    # Lyrics screensaver, screensaver delay and animation speed in Settings > System:
    adb shell pm grant dev.multix.tvhome android.permission.WRITE_SECURE_SETTINGS
    # Sort apps by recent use:
    adb shell appops set dev.multix.tvhome GET_USAGE_STATS allow
    # Let the lyrics react to the music (bass, vocals, highs, tempo) through
    # Visualizer(0) on the output mix. Nothing is recorded or stored:
    adb shell pm grant dev.multix.tvhome android.permission.RECORD_AUDIO

### How now playing finds the track

1. **Media session API.** Used when notification listener access is granted.
   It gives full metadata and artwork.
2. **Spotify broadcasts** (`com.spotify.music.metadatachanged` and
   `playbackstatechanged`). No permission needed.
3. **`dumpsys media_session`.** Used when the app holds DUMP. It polls every
   2 s while playing and every 5 s otherwise, only while the home screen or the
   screensaver is visible, and never on the main thread. The dump is parsed in
   memory and never stored or logged. The position is extrapolated between
   polls, so lyrics stay smooth.

When the source has no duration or cover, they come from the iTunes Search API.
Only the artist and the title are sent. The cover is cached in `files/art` as a
384 px JPEG of about 30 KB, with the newest 150 tracks kept. Lyrics are cached
in `files/lyrics`. A miss is retried after 3 days.

### Testing without playing anything

`fakeplayer/` is a tiny debug app that publishes a silent media session with
real track metadata and generated cover art. It plays no audio.

    ./gradlew :fakeplayer:assembleDebug
    adb install -r fakeplayer/build/outputs/apk/debug/fakeplayer-debug.apk
    adb shell am start -n dev.multix.fakeplayer/.Main --es title "'Blinding Lights'" --es artist "'The Weeknd'" --es album "'After Hours'" --el duration 200040 --el pos 30000
    adb shell am start -n dev.multix.fakeplayer/.Main --es cmd pause    # or play, stop

## How it stays smooth

- **Plain Android Views**, with no Compose and no third-party UI libraries.
  The only dependency is androidx.palette.
- **Motion runs on RenderNode properties.** Wallpaper drift, focus, scrolling,
  lyric slides and progress are all scale, translation and alpha. Nothing
  re-records or redraws bitmaps per frame. Group fades use short-lived hardware
  layers.
- **Bitmaps match their drawn size.**
  - Wallpapers decode at 1280x720 at most, with inSampleSize plus exact
    density scaling.
  - Cards and icons are rendered once, in the background, at their focused
    size.
  - Blurs are computed once on a tiny bitmap and stretched by the GPU.
- **One background thread** does every piece of disk, network and image work.
- **Nothing runs while the screen is hidden.** The drift, the clock, the
  wallpaper timer, the weather and the media polling all stop in `onStop`.
- **Overdraw is kept down.**
  - The window background is dropped once the wallpaper is up.
  - Wallpaper dim is a colour filter on the image, not an extra layer.
  - Opaque overlays hide the home screen underneath them.

## Credits and licenses

- **Fonts (SIL Open Font License, files in `licenses/`).**
  - Inter, IBM Plex Sans Arabic, DM Serif Display, Bebas Neue, Space Grotesk,
    JetBrains Mono, Playfair Display, Outfit, Readex Pro, Noto Kufi Arabic and
    Cairo.
  - Fonts without a Reserved Font Name are subset to the scripts in use to keep
    the APK small.
  - Playfair Display, Readex Pro and DM Serif Display ship unmodified.
- **Data services.**
  - Lyrics: [LRCLIB](https://lrclib.net).
  - Weather: [Open-Meteo](https://open-meteo.com).
  - Missing track length and cover: iTunes Search API.
- **Wallpapers.** See `../wallpapers/ATTRIBUTION.md`.

## Releases and in-app updates

Settings > About > Check for updates reads the latest release of github.com/MultiX0/sukoon-tv and installs its
`.apk` after the user confirms. To publish one:

1. Add the repository secrets once (Settings > Secrets and variables > Actions):
   - `SUKOON_KEYSTORE_B64`: `base64 -w0 launcher/keystore/sukoon-release.jks`
   - `SUKOON_STORE_PASSWORD`, `SUKOON_KEY_ALIAS`, `SUKOON_KEY_PASSWORD`: the values in `launcher/keystore.properties`
2. Push a tag: `git tag v1.1.0 && git push origin v1.1.0`.
   `.github/workflows/release.yml` runs the unit tests, builds the APK with the version from the tag
   (1.1.0 becomes version code 10100), signs it with the release key and attaches `sukoon-launcher-1.1.0.apk`.

Every release must be signed with the same key as the copy on the TV, or the updater refuses it. Keep
`keystore/sukoon-release.jks` and its passwords backed up; without them no installed copy can be updated.

Testing without GitHub: run a mock of the releases API on the computer, `adb reverse tcp:8099 tcp:8099`, then
`adb shell "am start -n dev.multix.tvhome/.HomeActivity --es update_api http://127.0.0.1:8099 --es settings about"`.
Only `http://127.0.0.1` or `http://localhost` are accepted, and `--es update_api ''` goes back to GitHub.
