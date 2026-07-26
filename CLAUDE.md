# shiroikuma-mpvkakucho

**白い熊 mpv拡張** — a fork of [mpvEx](https://github.com/marlboro-advance/mpvEx) (Apache-2.0), a
Jetpack-Compose video player built on mpv-android (itself forked from mpvKt). Package
`shiroikuma.mpvkakucho`, label **"白い熊 mpv拡張"**, installable side-by-side with stock mpvEx.

## Branch & remote model (same as the sister forks)

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-mpvkakucho.git` (ssh) — our fork.
- `upstream` = `https://github.com/marlboro-advance/mpvEx.git` (https, fetch only).
- **`master`** mirrors `upstream/master` (currently the `1.2.9` line). Fast-forward only — no fork
  work ever lives here.
- **`custom`** carries all our work, rebased onto `master` on each upstream sync. **All development
  happens on `custom`**, and it is the GitHub default branch.
- **Do not rename the `app.marlboroadvance.mpvex` code namespace** — only the installed
  `applicationId` differs (`shiroikuma.mpvkakucho`). Renaming would make every rebase a
  mass-conflict.

## Skills (`.claude/skills/`)

- **`build-apk`** — build the signed release APK via the `buildApk` Gradle task, then deliver it
  automatically via the global `/after-build` skill (adb push to `/sdcard/tmp/` if the phone is
  reachable, else scp to skhw) — **no transfer prompt**, never pause to ask how to transfer.
- **`upstream-new-version`** — check upstream for new commits; **⛔ before any rebase, present a
  proceed-gated descriptive table of the new upstream version's features and wait for 白い熊's
  explicit go-ahead**; then fast-forward `master`, rebase `custom`, reset `BUILD_NUMBER`, build the
  new `+1`.
- **`publish-version`** — publish the latest tested APK as a GitHub release: tag `<version>` (no `v`
  prefix), attach the APK, refresh README + `CHANGELOG-shiroikuma.md`, keep the default branch on
  `custom`. Pin `gh` with `-R ShiroiKuma0/shiroikuma-mpvkakucho` (the `upstream` remote otherwise wins).

## Build, versioning, signing

- **Build env (this machine):** default `java` is JDK 11 (can't run modern Gradle). Always:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk`.
- **Build:** `./gradlew buildApk` (release, signed; copies the APK to `~/tmp` and bumps
  `BUILD_NUMBER`). Fast dev iteration: `./gradlew :app:assembleStandardDebug` (no R8; the debug
  variant has `applicationIdSuffix = ".debug"` so it installs alongside the release build).
- **Native stack:** mpv + FFmpeg ship as the checked-in prebuilt
  `app/libs/mpv-android-lib-v0.0.1.aar` (~78 MB) — **no NDK toolchain work**, no submodules.
- **Toolchain:** Gradle 9.4.1, AGP 9.1, Kotlin 2.3.20, JVM target 17, compileSdk/targetSdk 36,
  minSdk 26. Room + KSP, Koin DI, Compose Navigation3.
- **Versioning:** upstream's `versionCode`/`versionName` literals in `app/build.gradle.kts` are left
  **byte-identical to upstream** and read back by the fork block further down, so an upstream bump
  flows through with no hand-editing and no conflict on those lines. `BUILD_NUMBER`
  (`gradle.properties`) is our increment — bumped every build, reset to 1 on each new upstream
  version. Fork `versionName = "<upstream>+<BUILD_NUMBER>"`,
  `versionCode = <upstream versionCode> * 10000 + BUILD_NUMBER` (129 → `1290001`).
- **Single-ABI arm64-v8a.** Upstream enables ABI **splits** (4 ABIs + universal APK) plus an
  `androidComponents` block that multiplies each output's versionCode by 10 and adds a per-ABI
  digit. The fork **disables splits and deletes that block**, so exactly one APK is produced and the
  versionCode is exactly `<upstream> * 10000 + <BUILD_NUMBER>`. **Never restore either** while
  resolving a rebase conflict.
- **Flavors:** upstream ships `standard` / `playstore` / `fdroid`. We build **`standard`** (full file
  access + in-app updater); `buildApk` targets `assembleStandardRelease`.
- **APK filename:** `shiroikuma-mpvkakucho_<versionName>_arm64-v8a.apk`. AGP 9 removed the legacy
  `applicationVariants` API the sister forks use to rename build outputs, so the house name is
  applied by the `buildApk` copy step rather than by renaming inside `build/outputs/`.
- **Signing:** release signed from the **gitignored** `keystore.properties`
  (`keystore.properties_sample` documents the keys) → `~/.android-keystores/shiroikuma-mpvkakucho.jks`
  (alias `mpvkakucho`). Password recorded in `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`
  (jks backup in `android-keystores/` next to it). Losing both loses the signing identity.
  Upstream has **no** local signing config at all (its CI signs with `apksigner` from a repo secret),
  so the entire `signingConfigs` block is ours; it tolerates a missing `keystore.properties` (the
  release APK then simply comes out unsigned).
- **`gradlew` executable bit:** upstream tracks `gradlew` as mode `100644`, so a fresh clone cannot
  run it. Our `custom` branch marks it `100755`. If a rebase reverts the mode, `chmod +x gradlew`.
- **Delivery:** APK to `~/tmp`, then `/after-build` (adb push to `/sdcard/tmp/` or scp to skhw);
  **白い熊 installs from the on-device file manager** (never `adb install`).

## Working rules (override harness defaults where noted)

- **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or PR bodies — end
  the message at the last line of the body. (Overrides the harness default; global rule in
  `~/.claude/CLAUDE.md`.)
- **Never commit or push until 白い熊 says "Push".** Treat the working tree as scratch between
  "Push" commands; multiple uncommitted fixes can stack. "Push" = `git commit` + `git push origin
  custom` (and `master` after an upstream sync). 白い熊 tests each build on-device first.
- **After every successful build, deliver the APK automatically via `/after-build`** — never ask how
  to transfer it, never pause.
- **Commit subjects:** plain descriptive summary, no prefix.
- Fork changelog notes go to `CHANGELOG-shiroikuma.md` only.
- `git push` / `gh` / `scp` need `~/.ssh` and `~/.config/gh`, which the command sandbox blocks — run
  those with `dangerouslyDisableSandbox: true`. **Any write under `~/git` needs it too** (the
  sandbox only grants write access to the scratch dir and `~/tmp`).

## Fork identity (the standing customization layer)

| What | Value | Where |
| --- | --- | --- |
| App id | `shiroikuma.mpvkakucho` | `app/build.gradle.kts` → `val packageName` |
| Namespace | `app.marlboroadvance.mpvex` (**never rename**) | `app/build.gradle.kts` → `android.namespace` |
| Label | `白い熊 mpv拡張` | `app_name` in `app/src/main/res/values/strings.xml` |
| Icon | black-yellow traced mark (yellow `#FFFF00` line-art on black) | `mipmap-*/ic_launcher*`, `mipmap-anydpi-v26/`, `drawable/ic_launcher_foreground.xml` |
| Version logic | `shiroikumaBuild` + the `* 10000` fork block + `buildApk` task | `app/build.gradle.kts` |
| Signing | `keystore.properties` (gitignored) → `~/.android-keystores/shiroikuma-mpvkakucho.jks` | `app/build.gradle.kts` |
| Single ABI | `abiFilters += "arm64-v8a"` + `splits.abi.isEnable = false` | `app/build.gradle.kts` |
| Updater target | `ShiroiKuma0/shiroikuma-mpvkakucho` releases | `utils/update/UpdateFeature.kt` |

The in-app updater (`standard` flavor, `ENABLE_UPDATE_FEATURE=true`) points at **our** releases, not
upstream's. That is deliberate: upstream builds are signed with a different key and could never
install over ours, so offering them as "updates" would be both broken and wrong branding. The
`publish-version` skill's tag format (`<upstream>+<N>`, no `v` prefix) is what the updater compares
against, so the two must stay in step.

## Repo layout (upstream mpvEx)

- `app/src/main/java/app/marlboroadvance/mpvex/`
  - `ui/player/` — the mpv playback surface, gesture handling, on-screen controls.
  - `ui/browser/` — the file/library browser, including the network backends (SMB via smbj, FTP via
    commons-net, WebDAV via sardine, plus a NanoHTTPD server).
  - `ui/preferences/` — the Compose settings screens (decoder, subtitles, audio, gestures, advanced).
  - `ui/mediainfo/` — the MediaInfo track inspector.
  - `database/`, `repository/`, `domain/` — Room storage for playback history and playlists.
  - `di/` — Koin modules. `utils/update/` — the in-app updater.
- `app/src/main/res/` — `values/strings.xml` holds `app_name` and `github_repo_url`; launcher assets
  are `.webp` mipmaps plus adaptive XML in `mipmap-anydpi-v26/` and a vector foreground in
  `drawable/ic_launcher_foreground.xml`.
- `website/` and `fastlane/` — upstream's marketing site and store metadata; not built by us.
- `.github/workflows/` — upstream's CI (signs with a repo secret we don't have, publishes 4 ABI
  splits). **Not used**; our releases are cut by hand via `publish-version`.

## Current status

**Phase 0 — repo bootstrap (2026-07-26).** Fork created from `marlboro-advance/mpvEx`;
`master` mirrors upstream, `custom` created with the identity layer: app id `shiroikuma.mpvkakucho`,
label `白い熊 mpv拡張`, fork versioning (`+N` / `×10000`), single-ABI arm64 build with upstream's ABI
splits and per-ABI versionCode block removed, house APK naming, own keystore + gitignored
`keystore.properties`, and the three skills above. Icon and full de-branding follow next, then the
first build.
