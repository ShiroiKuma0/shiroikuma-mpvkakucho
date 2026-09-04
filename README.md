<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="120" alt="白い熊 mpv拡張 icon" />

# 白い熊 mpv拡張

**An mpv-powered video player for Android — black, yellow, and yours to shape.**

A fork of [mpvEx](https://github.com/marlboro-advance/mpvEx) with **major additions**: a full
black-yellow UI customization page, portrait laid out exactly like landscape, one-ZIP
export/import of everything settable, headless backup automation that survives a wiped phone, and
restored chapter markers.

Installs **side-by-side** with mpvEx (app id `shiroikuma.mpvkakucho`).

**📥 Latest release: [`1.2.9+8`](https://github.com/ShiroiKuma0/shiroikuma-mpvkakucho/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-mpvkakucho/releases)

</div>

---

## 🎨 The 白い熊 mpv拡張 UI page

A customization page that actually reaches the app. Pure black with pure yellow `#FFFF00` is the
**default** — no setup, no Material You, no wallpaper palette. From one page you set every colour,
font, size, border and spacing, and each group shows a **live preview** of exactly what it drives.

Colour pickers are four **RGBA** sliders over a live swatch, with one-click choice boxes above
prefilled with the house palette and every colour you have used before. Every size is a slider, and
every border, roundness, divider and gap slider reaches **0** — off is always reachable.

Reach it from Settings, or **long-press the settings cog** on any browser screen.

---

## 🔤 Your own fonts

Import any `.ttf`/`.otf` into the app, then pick it for the whole UI — with weight and text scale on
their own sliders. The font picker renders **every option in its own glyphs**, so you choose by
looking at the typeface, not at its filename.

---

## 📱 Portrait is not a lesser mode

Upstream treated portrait as a cut-down layout. Here it is not:

- All four control regions — top-left, top-right, bottom-left, bottom-right — in **both**
  orientations, from the same settings. Upstream gave portrait a single hard-coded strip and no
  top-right or bottom-left region at all.
- Identical geometry in both: pause button centred, seekbar at the bottom, control rows above it.
- The chapter chip renders in portrait (upstream drew nothing there).
- Every button row scrolls horizontally, so a crowded region never clips at any width.
- Playlist sheet: list/grid toggle available in portrait, saved view mode honoured, no half-height cap.
- Browser grid columns 1–8 in both orientations, not 4/3 in portrait against landscape's 5.

---

## 💾 One ZIP, everything settable

Export and import the whole app as a single `.zip` of plain JSON — the UI theme, imported fonts, all
app settings, playlists, playback history and network connections, each independently selectable.
Import **merges**: absent categories are skipped and settings are merged key by key, so an old
backup never destroys state it does not mention.

Pick an export directory once and the page tells you when you last backed up.

---

## 🤖 Headless backup automation

The app answers an `EXPORT_STATE` broadcast, so a sister-app task can back it up with no UI at all —
reporting real progress counts and replying with the written path and size. It also enumerates its
own categories on request, stating which of them start ticked, so the caller's picker is told the
answer rather than guessing it. A running export can be **cancelled** from outside: it unwinds at
the next entry boundary and deletes its half-written file, leaving the backup directory exactly as
it found it.

Automation is **on out of the box**, and an authorization token is opt-in — a pasted secret cannot
survive a wipe, and the point is to work on a phone where nothing has been configured yet. Turn
「Use authorization token?」 on and a caller must present the token as well; leave it off and any
sister app may drive the export. A token sent to the app when it is not asking for one is quietly
ignored rather than refused. The token itself never travels inside a backup.

---

## 💾 Backup that survives a clean phone

A second, authenticated door lets a backup manager save this app **with its data** and put it back
on a wiped phone — the thing that normally needs root. It identifies the caller three ways (exact
package name, uid, and a pinned signing certificate) and moves the archive through a file descriptor
the caller opens, so the backup lands inside that manager's encrypted, checksummed archive rather
than beside it. Restore exists only on this door, never as a broadcast, so no other app on the phone
can overwrite your watch positions.

What travels is the app's **state** — the UI theme, settings, playlists, watch positions and network
connections, a few hundred kilobytes of it. **Video files are never included**: a playlist entry is
a path, not a copy.

---

## 📖 Chapters that work again

Upstream 1.2.9 had removed chapter markers from both seekbar renderers, and its Chapters sheet
bailed out whenever mpv reported the current chapter as `-1` — which it does before the first
chapter starts. Chapter boundaries are drawn again, with a settable marker width (0 = off), and the
Chapters sheet lists chapters whenever the file has any.

---

## Built on mpvEx

A fork of [mpvEx](https://github.com/marlboro-advance/mpvEx) (app id `shiroikuma.mpvkakucho`, so it
coexists with the official build), itself built on mpv-android and forked from mpvKt. Upstream does
the hard part — a genuinely good Jetpack Compose front-end over mpv, with the whole FFmpeg/mpv stack
behind it — and this fork is a personal re-skin and extension on top of that work. The code remains
under the **Apache License 2.0**.

## Building

```bash
git clone git@github.com:ShiroiKuma0/shiroikuma-mpvkakucho.git
cd shiroikuma-mpvkakucho

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/android-sdk"

# Signed release APK -> ~/tmp/shiroikuma-mpvkakucho_<version>_arm64-v8a.apk
./gradlew buildApk

# Fast dev iteration (no R8; installs beside the release build)
./gradlew :app:assembleStandardDebug
```

Release signing reads a gitignored `keystore.properties` at the repo root
(`keystore.properties_sample` documents the keys); without it the release APK simply comes out
unsigned. Single-ABI **arm64-v8a**; `versionCode` is `<upstream> * 10000 + <build>`.
