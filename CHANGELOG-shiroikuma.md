# 白い熊 mpv拡張 — fork changelog

Changes this fork makes on top of stock [mpvEx](https://github.com/marlboro-advance/mpvEx).
Upstream's own release notes are not duplicated here.

## 1.2.9+1 — current

Base: upstream mpvEx `1.2.9` (versionCode 129).

### Identity

- Installed as `shiroikuma.mpvkakucho`, label **白い熊 mpv拡張** — side-by-side with stock mpvEx.
  The `app.marlboroadvance.mpvex` code namespace is deliberately unchanged so upstream rebases
  stay clean.
- Launcher icon redrawn in the house style: the mpvEx play mark boundary-traced into a
  stroke-only outline, yellow `#FFFF00` on black, across the adaptive foreground, the legacy
  square and round tiles, the themed (monochrome) icon and the notification small icon.
- De-branded: the About screen, the storage-permission explainer, the MediaInfo export footer and
  every in-app GitHub link now name this fork and point at
  `ShiroiKuma0/shiroikuma-mpvkakucho`. Exported settings files are named `mpvkakucho_settings_*.xml`
  and the M3U fetcher's User-Agent is `mpvkakucho/1.0`.
- Upstream's "Donate" section (the upstream author's Ko-fi / PayPal / UPI details) is removed from
  the About screen.

### The black-yellow theme is the default

- New **白い熊** app theme: pure black with pure yellow `#FFFF00` (not Material's amber `#FFEB3B`).
  It is listed first and is the **default**, alongside dark mode and AMOLED — a fresh install is
  black-and-yellow with no user action. Upstream defaulted to Dynamic (Material You, i.e. the
  wallpaper palette), System dark mode and AMOLED off.
- Its colour scheme is built explicitly rather than by upstream's generic builders: every
  `*Container` role is a flat near-black surface and `surfaceTint` is transparent. Upstream's
  builders composite a low-alpha accent over the background, which with yellow on black lands on
  **olive**; the transparent tint also stops Material's tonal-elevation overlay from pulling
  surfaces back toward the accent. Alpha yellow is still used for outlines, where as a stroke over
  black it reads as the intended dim-yellow divider.
- The theme has no light variant — it stays black-yellow in every mode. Other themes are untouched.
- The Android window theme was `…DynamicColors.DayNight.NoActionBar`, which resolves to a **light**
  window on a light-mode system (a white flash before Compose draws). It is now pinned dark with a
  pure black `windowBackground`.
- Player overlay content is yellow under the house theme; other themes keep upstream's white, since
  a theme-derived colour would go dark and unreadable on the light ones.

### Portrait parity — no baked-in portrait limits

Upstream treated portrait as a cut-down mode. On a wide/folding screen that is pure loss, so
portrait now behaves exactly like landscape:

- **All four control regions in both orientations.** Upstream gave portrait a single hard-coded
  bottom strip (`portraitBottomControls`) and rendered no top-right or bottom-left region at all.
  Portrait now uses the same top-left / top-right / bottom-left / bottom-right regions as
  landscape, driven by the same preferences — whatever you configure applies to both.
- The layout editor's separate "Portrait Bottom" region is gone; the settings screen now has one
  **"Player Controls (portrait & landscape)"** section.
- **Identical geometry**: the pause button is centred on screen, the seekbar sits at the bottom and
  the control rows sit above it, in both orientations. Portrait no longer gets its own anchoring.
- **The chapter chip renders in portrait.** Upstream's `CURRENT_CHAPTER` button drew literally
  nothing when portrait.
- **Every button row scrolls horizontally**, so a crowded region is never clipped at any width.
- **Playlist sheet**: the list/grid toggle is available in portrait (upstream: landscape only), the
  saved view mode is honoured in both orientations instead of being forced to list in portrait, and
  the portrait-only half-screen height cap is gone.
- **Browser grid columns**: 1–8 in both orientations. Upstream capped portrait at 4 folder columns
  and 3 video columns while allowing landscape 5.

### Build & packaging

- Fork versioning: `versionName = "<upstream>+<BUILD_NUMBER>"`,
  `versionCode = <upstream> * 10000 + BUILD_NUMBER` (129 → `1290001`). Upstream's two literals are
  left byte-identical and read back, so an upstream bump needs no hand-editing.
- Single **arm64-v8a** APK: upstream's ABI splits and its `androidComponents` block (which
  multiplied each output's versionCode by 10 and added a per-ABI digit) are removed.
- Release signing added — upstream has no local signing config at all, only CI secrets. Credentials
  come from a gitignored `keystore.properties`; a missing file degrades to an unsigned build rather
  than failing configuration.
- `buildApk` task: assembles the `standard` release flavor, copies the APK to `~/tmp` as
  `shiroikuma-mpvkakucho_<version>_arm64-v8a.apk` and bumps `BUILD_NUMBER`.
- `gradlew` marked executable (upstream tracks it as mode `100644`, so a fresh clone can't run it).

### Fixes

- **In-app updater never offered fork builds.** It compared versions by splitting on `.` and
  `toIntOrNull()`, so our `1.2.9+1` parsed its last component as `"9+1"` → `0`; every fork build
  compared equal to every other. The comparator now parses the `+N` build tail, and the updater
  checks this fork's releases rather than upstream's (upstream builds are signed with a different
  key and could never install over ours).
