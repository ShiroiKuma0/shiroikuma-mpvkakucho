# 白い熊 mpv拡張 — fork changelog

Changes this fork makes on top of stock [mpvEx](https://github.com/marlboro-advance/mpvEx).
Upstream's own release notes are not duplicated here.

## 1.2.9+5 — 2026-07-31

Base: upstream mpvEx `1.2.9` (versionCode 129) — unchanged; upstream has had no commits since March.

### 保存復元 automation — categories state their own default, and an export can be cancelled

- **`LIST_CATEGORIES` now emits the contract's fourth field**, `id⇥label⇥parent⇥on|off`, with an
  empty third field on top-level items. The flag says whether an item **starts ticked**, so a
  caller's backup-item picker is told the app's answer instead of assuming everything. Nothing this
  app exports is large, derived *and* re-creatable — the case the `off` flag exists for — so every
  category is `on`; the value of sending it is that the app states the default and any category
  added later inherits a field that is already there.
- The flag lives on the category definition itself (`Cat.defaultOn`), and **the in-app
  Export/Import picker seeds from the same flag**, so the in-app sheet and an automation picker
  start from one answer rather than two guesses. "No selection given" on the export side — an
  absent `items` extra — now resolves to the `on` set rather than to every entry.
- **New `CANCEL_EXPORT` action** on the same exported receiver, with the same token gate and an
  optional `reply_id` (absent = whatever is running, unambiguous because two exports at once are
  forbidden). It is declared on the *receiver* deliberately: a third-party caller cannot start an
  `exported="false"` service, so a stop path living on one would be unreachable.
- The cancel is **fire-and-forget — it answers nothing at all**, not even a refusal, and it is a
  **silent no-op** when nothing is running, when it names a different request, or when the export
  has already finished. Safe to send at any time.
- The export unwinds at the **next entry boundary** — a `@Volatile` flag read between top-level
  categories, between font files and before the manifest — never by interrupting a thread
  mid-`write()` and never by killing the process.
- **A cancelled export leaves the backup directory exactly as it found it.** The half-written
  destination is deleted, on cancel and on every other failure, for both the plain-file and SAF
  destinations — no short archive left behind for the next "latest export" query to find.
- The original request still gets its terminal reply, `ERROR:cancelled`, through the normal
  broadcast channel and guarded by the same single-fire latch, so it can never double-fire with a
  success. It is sent even though nobody may still be listening: it is what proves the run ended
  rather than continuing unseen.
- **The in-app export routes through the same path** — it registers with the same run flag, so a
  cancel broadcast stops it too, and it deletes its own partial file on failure or cancel, for both
  the configured-directory and the save-as destination. One way to unwind, not two.

## 1.2.9+4 — 2026-07-26 (first release)

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

### 白い熊 mpv拡張 UI page

A house-style customization page, reachable from **Settings → 白い熊 mpv拡張 UI** and by
**long-pressing the settings cog** on any browser screen.

- **kxkb page look**: every section is a big bold accent heading underlined only as wide as its own
  text, preceded by a thin full-width hairline; items sit one indent step under their heading and
  sub-levels one step further, so the level is obvious at a glance. Row padding is deliberately
  tight — the only generous space is between top-level sections.
- **Live, with previews.** Each group carries a preview of exactly what it controls (colour swatches
  and divider, type specimen, border/corner sample, a mock player overlay with seekbar, a mock
  browser row). Every change repaints the app immediately.
- **Colour pickers** are 4-channel **RGBA** sliders over a live preview, with one-click choice boxes
  above them prefilled with the house palette and every colour previously applied.
- **External fonts**: import `.ttf`/`.otf` into the app, pick per-app font, delete again. **Every
  option in the picker renders in its own glyphs.**
- **Every size is a slider**, and all border/thickness/roundness knobs bottom out at **0** (= off).
- Settable: background · surface · accent · primary and secondary text · border · divider ·
  selection · error; player control tint, overlay text, seekbar played/buffered/track, seekbar
  height, control button size and gap, overlay dim; border and card-border width, corner roundness,
  divider thickness; font, weight, text scale, title/body/label sizes; browser row padding, row gap,
  thumbnail size and roundness; and the settings pages' own heading size, underline, indent step and
  row padding. Plus a one-tap restore of the black-yellow defaults.
- These are not cosmetic-only: the player's control tint, seekbar colours, button size/gap and
  overlay dim are read live by the player itself, and the font/weight/scale ride on the app's
  typography.

### Export / Import — one ZIP of everything settable

The first section of the UI page, in the Kōjiki flow:

- A settable **export directory**, queried on opening the page for the latest export. The
  "no directory set" message is **red** until one is chosen, then yellow — on the page and in the
  panel.
- The panel lists categories with a **Select all** master toggle and sub-options indented under
  their parent: 白い熊 UI (with *Imported font files* as a sub-option) · app settings · playlists ·
  playback history · network connections.
- Button line in the ArcaneChat shape: round pills, **Cancel alone on the left**, **Import and
  Export on the right**.
- Success shows a black/yellow-bordered **OK** dialog. Acknowledging it closes the whole chain —
  the info dialog, the Export/Import panel beneath it, and the UI settings page. The import variant
  offers **Later** (same chain close) and **Restart now**. Failures ("Export failed…",
  "No categories selected.") close only the info dialog and leave the panel open.
- One ZIP per export, named `shiroikuma-mpvkakucho_<yyyy-MM-dd_HH-mm-ss>.zip` per the family
  convention: `manifest.json` plus one JSON per category, fonts under `fonts/`. Import **merges** —
  absent categories are skipped and prefs are merged key by key, so an old backup never destroys
  newer state it does not mention.

### 保存復元 automation contract

The sister-app state-export contract, so 自由作業盤 can back this app up headlessly:

- `EXPORT_STATE` and `LIST_CATEGORIES` broadcast receivers, token-gated, **master switch default
  OFF**. The two rows live **inside** the Export/Import section, below the export rows.
- Replies are a fresh broadcast with `FLAG_INCLUDE_STOPPED_PACKAGES` — no binder, no reliance on
  the ordered-broadcast result, since EMUI severs both between third-party apps. Exactly one
  terminal reply, single-fire guarded.
- Progress broadcasts carry **real counts**, never a percentage, throttled to one per 500 ms.
- `path` overrides the configured directory (the app already holds `MANAGE_EXTERNAL_STORAGE`);
  precedence is `path` → configured directory → `ERROR:no-directory`.
- The token lives in a device-local prefs file that is **not** in the export, so it never travels
  in a backup ZIP.

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

- **Chapters were invisible and unlistable.** Upstream 1.2.9 had deleted chapter markers from
  **both** seekbar renderers — `StandardSeekbar` (which draws the default *Thick* style and
  *Standard*) hard-coded `val chapterGaps = emptyList()`, and `SquigglySeekbar`'s
  `drawPathWithGaps` was reduced to "draw continuous path". A file with chapters therefore drew as
  one unbroken bar. Both now punch a gap at every chapter boundary, with the gap width settable
  from the UI page (Player → Seekbar → **Chapter marker width**, 0 = off).
- **The Chapters sheet opened empty.** It bailed out with `if (chapter == null) return` whenever the
  *current* chapter could not be resolved — and mpv reports `chapter` as `-1` before the first
  chapter starts, so `chapters.getOrNull(-1)` was null and the sheet silently showed nothing even
  though the file had chapters (the toolbar's Chapters button only renders when chapters exist, so
  it was visible the whole time). Only an actually-empty chapter list suppresses the sheet now;
  otherwise it falls back to the first chapter.
- **In-app updater never offered fork builds.** It compared versions by splitting on `.` and
  `toIntOrNull()`, so our `1.2.9+1` parsed its last component as `"9+1"` → `0`; every fork build
  compared equal to every other. The comparator now parses the `+N` build tail, and the updater
  checks this fork's releases rather than upstream's (upstream builds are signed with a different
  key and could never install over ours).
