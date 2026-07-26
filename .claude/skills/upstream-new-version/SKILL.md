---
name: upstream-new-version
description: Sync the shiroikuma-mpvkakucho fork onto a newer upstream mpvEx (marlboro-advance/mpvEx) and rebuild. Checks upstream for new commits; ALWAYS presents a proceed-gated tabular summary of the new upstream version's features BEFORE any rebase; then fast-forwards master, rebases custom, resets BUILD_NUMBER, and builds the new +1. Use when 白い熊 runs /upstream-new-version, says a new mpvEx version is out, or asks to update/sync/bump to upstream, rebase custom onto upstream, or rebase-and-rebuild the fork.
---

# Sync shiroikuma-mpvkakucho onto a newer upstream mpvEx

This fork tracks [marlboro-advance/mpvEx](https://github.com/marlboro-advance/mpvEx).
`master` mirrors `upstream/master` (fast-forward only, never carries fork work); `custom` carries all
our patches and is rebased onto each new upstream tip.

> **Never `git push`, `git commit` or `adb install` unprompted.** After the rebase + build you stop
> and let 白い熊 test on-device. You push only when they explicitly say **"Push"**.

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Mirrors `upstream/master`. No fork work here. | fast-forward only |
| `custom` | Our patches; the working/dev branch. Default branch on GitHub. | rebased onto `master` each sync |

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-mpvkakucho.git` (ssh, **push here**).
- `upstream` = `https://github.com/marlboro-advance/mpvEx.git` (https, **fetch only**).
- Pin `gh` with `-R ShiroiKuma0/shiroikuma-mpvkakucho` — the `upstream` remote otherwise wins.

## Versioning

- Upstream's `versionCode` / `versionName` literals in `app/build.gradle.kts` are **left exactly as
  upstream writes them**. The fork block further down reads them back and derives ours, so an
  upstream bump flows through with **no hand-editing and no conflict on those two lines**.
- `BUILD_NUMBER` (in `gradle.properties`) is our increment: bumped by every `buildApk`,
  **reset to `1`** on each new upstream version.
- Fork `versionName = "<upstream>+<BUILD_NUMBER>"`;
  `versionCode = <upstream versionCode> * 10000 + BUILD_NUMBER` (129 → `1290001`).
- **Sanity-check the ceiling**: `new_vc * 10000 + 9999` must stay under Android's hard limit of
  `2100000000`, i.e. upstream's `versionCode` must stay under **210,000**. It is currently 129, so
  there is enormous headroom — but if upstream ever switched to a date-style code (e.g. `20260726`),
  stop and re-plan the multiplier with 白い熊 rather than shipping an uninstallable APK.

## Step 1 — check for a newer upstream version

```bash
cd ~/git/shiroikuma-mpvkakucho
git fetch upstream
git fetch origin

if git merge-base --is-ancestor upstream/master master; then
  echo ">>> No new upstream version — master is already at or above upstream/master."
else
  old_vn=$(git show master:app/build.gradle.kts          | grep -oP 'versionName = "\K[^"]+' | head -1)
  new_vn=$(git show upstream/master:app/build.gradle.kts | grep -oP 'versionName = "\K[^"]+' | head -1)
  old_vc=$(git show master:app/build.gradle.kts          | grep -oP 'versionCode = \K[0-9]+'  | head -1)
  new_vc=$(git show upstream/master:app/build.gradle.kts | grep -oP 'versionCode = \K[0-9]+'  | head -1)
  echo ">>> New upstream: versionName ${old_vn} -> ${new_vn}, versionCode ${old_vc} -> ${new_vc}"
  echo ">>> $(git rev-list --count master..upstream/master) new upstream commit(s)."
fi
```

If nothing is new, **stop here** — report the current version and that we are up to date. Do not ff,
do not rebase, do not build.

## Step 2 — ⛔ proceed-gated table of what the new upstream version introduces

**Mandatory on every single sync. Present the table, then WAIT.** Do not fast-forward `master`, do
not rebase, do not build until 白い熊 explicitly says proceed / continue / yes. The rebase is never
started silently — this is 白い熊's standing request, made when the fork was created.

Capture the old tip **before** any fast-forward, and read the commits:

```bash
old=$(git rev-parse master)     # capture BEFORE the Step 3 ff
git log --format='%h | %an | %s' "$old"..upstream/master
git log --stat --format='%n### %h  %s%n%b' "$old"..upstream/master   # bodies + files touched
gh release view -R marlboro-advance/mpvEx --json tagName,name,body -q '.tagName + "\n" + .body' | head -80
```

Present a **Markdown table**, one row per non-trivial change (fold the recurring Weblate/i18n
translation commits into a single "translations" row), with these columns:

| Column | What goes in it |
| --- | --- |
| **Commit** | short SHA |
| **Area** | subsystem — player/mpv core, decoder & hardware accel, subtitles, audio tracks, file browser, network protocols (SMB/FTP/WebDAV/HTTP), gestures & controls, PiP/background play, playlist & history, settings UI, theming, update feature, build |
| **What it changes** | a plain-language sentence drawn from the commit *body*, not just the subject — what is actually new or fixed, described so 白い熊 can judge it without reading the diff |
| **Relevance to this fork** | **High / Medium / Low, and why** — does it touch a file in our customization layer (identity, versioning, signing, icon, de-branding strings, the updater's repo URL), the bundled `mpv-android-lib` AAR, or a feature 白い熊 actually uses? Flag anything likely to **conflict on rebase** and anything that is a **genuinely useful fix** |

Then add a short **"New features"** section in prose for anything user-visible that the table's
one-liners undersell (a new screen, a new gesture, a new protocol backend, a new decoder option),
and end with a one-line takeaway — e.g. "one valuable fix (the HDR tone-mapping regression) plus a
subtitle-styling batch; the only thing touching our layer is `strings.xml`, so expect one small
conflict on the de-branded strings".

**Then stop and wait for the go-ahead.**

## Step 3 — fast-forward master, rebase custom (after the go-ahead)

```bash
cd ~/git/shiroikuma-mpvkakucho
git status --short          # must be clean before rebasing

git checkout master
git merge --ff-only upstream/master

git checkout custom
git rebase master
```

Do **not** push here — both pushes are deferred to Step 7. If the rebase goes irrecoverable,
`git rebase --abort` and re-plan with 白い熊 (an aborted rebase leaves `custom` untouched; `master`
stays safely fast-forwarded).

## Step 4 — reconcile conflicts

Re-derive the *intent* against the new upstream files rather than blindly taking either side. If
upstream restructured a file we patch, port our change to the new structure.

**If the conflicts are significant, stop and plan with 白い熊 before continuing.**

Conflict-prone files, and the shape each must end up in:

- **`app/build.gradle.kts`** — the likeliest conflict. Keep all of:
  1. `val packageName = "shiroikuma.mpvkakucho"` (and `android.namespace = "app.marlboroadvance.mpvex"`
     **unchanged**), with `applicationId = packageName` in `defaultConfig`.
  2. `val shiroikumaBuild = (providers.gradleProperty("BUILD_NUMBER").orNull ?: "1").toInt()`.
  3. The single-ABI `ndk { abiFilters += "arm64-v8a" }` in `defaultConfig`.
  4. `splits { abi { isEnable = false } }` — **and the continued absence of upstream's
     `androidComponents` per-ABI versionCode block**. If the rebase brings that block back, delete it
     again; leaving it in multiplies our versionCode by 10 and breaks the documented scheme.
  5. The fork-version block (`upstreamVersionCode`/`upstreamVersionName` read back,
     `* 10000 + shiroikumaBuild`). **Upstream's own `versionCode`/`versionName` lines inside
     `defaultConfig` stay untouched** — if a conflict lands there, take *upstream's* side verbatim.
  6. The whole `signingConfigs` block + `signingConfig = signingConfigs.getByName("release")` on the
     release build type (upstream has no local signing config — ours is pure addition).
  7. The `buildApk` task at the end of the file.
- **`gradle.properties`** — keep `BUILD_NUMBER` (and reset it, Step 5). Keep upstream's other flags.
- **`.gitignore`** — keep `/keystore.properties` ignored and `.claude/settings.local.json` ignored;
  `CLAUDE.md` and `.claude/skills/` stay **committed**.
- **`app/src/main/res/values/strings.xml`** — `app_name` = `白い熊 mpv拡張`, `github_repo_url` =
  our fork, and every other de-branded string. Upstream edits this file often.
- **`app/src/main/java/app/marlboroadvance/mpvex/utils/update/UpdateFeature.kt`** — the in-app
  updater's release API URL must stay pointed at **`ShiroiKuma0/shiroikuma-mpvkakucho`**, never
  upstream. A rebase that silently restores upstream's URL would make the app offer 白い熊 *stock
  mpvEx* builds as "updates" — they would fail to install (different signing key) and, worse, would
  advertise the wrong app.
- **Icon assets** — `mipmap-*/ic_launcher*.png`, `mipmap-anydpi/ic_launcher*.xml` and any in-app
  logo drawable must stay our black-yellow traced icon. A binary conflict here means upstream redrew
  theirs — keep **ours**.
- **`gradlew`** — we set the executable bit (upstream tracks it as mode `100644`, which cannot be
  run directly). If the mode reverts, `chmod +x gradlew` again.

## Step 5 — reset the build tail

In `gradle.properties`, set **`BUILD_NUMBER=1`** — the new upstream line starts its `+N` at 1.

## Step 6 — verify the customization layer survived, then build

| What | Expected value | Where |
| --- | --- | --- |
| Installed app id | `shiroikuma.mpvkakucho` | `app/build.gradle.kts` → `val packageName` |
| Code namespace | `app.marlboroadvance.mpvex` (**never rename**) | `app/build.gradle.kts` → `android.namespace` |
| App label | `白い熊 mpv拡張` | `app_name` in `app/src/main/res/values/strings.xml` |
| Launcher icon | black-yellow traced mark | `mipmap-*/ic_launcher*`, `mipmap-anydpi/` |
| Fork version logic | `shiroikumaBuild`, `* 10000 +`, `forkVersionName` | `app/build.gradle.kts` |
| No per-ABI code block | upstream's `androidComponents { … abiCodes … }` still **absent** | `app/build.gradle.kts` |
| Single ABI | `abiFilters += "arm64-v8a"`, `splits.abi.isEnable = false` | `app/build.gradle.kts` |
| APK naming | `shiroikuma-mpvkakucho_…_arm64-v8a.apk` | `buildApk` task |
| Signing | `keystore.properties` → `~/.android-keystores/shiroikuma-mpvkakucho.jks` | `app/build.gradle.kts` |
| Updater target | `ShiroiKuma0/shiroikuma-mpvkakucho` releases | `utils/update/UpdateFeature.kt` |
| Build tail | `BUILD_NUMBER=1` | `gradle.properties` |
| De-branding | no "mpvEx"/"mpvKt"/`marlboro-advance` links in user-visible strings, About or Help | `values/strings.xml`, About/settings screens |
| Committed agent files | `CLAUDE.md`, `.claude/skills/` tracked | `.gitignore` |

Sanity-check that the script still evaluates, then build the new `+1` via the **build-apk** skill:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
./gradlew :app:tasks --console=plain | head      # config sanity
./gradlew buildApk --console=plain < /dev/null   # the new <newVersion>+1
```

`build-apk` then delivers the APK automatically via the global **`/after-build`** skill (adb push if
the phone is reachable, else scp to skhw — no prompt, no transfer question).

## Step 7 — push ONLY after 白い熊 tests and says "Push"

Stop after the build with a signed APK delivered, and **wait**. On their explicit **"Push"**:

```bash
cd ~/git/shiroikuma-mpvkakucho
git checkout master
git push origin master                        # fast-forward, safe

git checkout custom
git push --force-with-lease origin custom     # rebased history
```

## One-line summary of the flow

`fetch upstream` → new version? (else stop) → **tabular feature summary + WAIT for go-ahead** →
ff `master` → rebase `custom` (reconcile per Step 4) → `BUILD_NUMBER=1` → verify the layer →
**build the new `+1` via build-apk** → 白い熊 tests → on "Push": push `master`, force-with-lease `custom`.

## Hard rules

- Never `adb install` / `adb uninstall` — 白い熊 installs manually from `/sdcard/tmp/`.
- Never commit or push unprompted; wait for **"Push"**.
- Never rename the `app.marlboroadvance.mpvex` namespace — only the installed `applicationId` differs.
- Never restore upstream's ABI splits or its per-ABI `androidComponents` versionCode block.
- Never let the in-app updater point back at upstream's repo.
- `git push` / `gh` / `scp` need `~/.ssh` and `~/.config/gh`, which the command sandbox blocks — run
  those with `dangerouslyDisableSandbox: true`. Writes anywhere under `~/git` need it too.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of
the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
