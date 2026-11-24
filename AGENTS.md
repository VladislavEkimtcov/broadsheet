# Agent instructions: broadsheet

Apple News (by way of Apple News Relay on a Mac) on three front ends in two apps that share no
code:

- **Android** in `android/` (Java, bare Android SDK, no Gradle, no libraries): one APK, package
  `io.github.vladislavekimtcov.broadsheet`, for TVs and phones. One Java package in three
  source roots: `android/src/` shared by both (relay client, the controller `Main`, reader,
  options, player, cards), `android/tv/src/` the TV's front page, `android/phone/src/` the
  phone's.
- **Windows** in `windows/` (C# 5, WPF, .NET Framework 4.8 in-box toolchain).

Shared: `assets/` (fonts, read by both builds), `spec/cases/` (behaviour both test suites
read), `tools/`, `docs/` (screenshots per platform), `version.env` (one version for all).
Keep them at feature parity through [PARITY.md](PARITY.md).

## Parity rules

1. **Any user-visible change updates `PARITY.md` in the same commit.** New feature: add a row
   with a new stable ID and a cell for TV, Phone and Windows. Changed feature: check the row
   still says what each front end does.
2. **Port it, or mark it owed.** If you change only some front ends, set the others' cells to
   `⏳ <version it appeared in>`. Deliberate differences are `⚠️` or `—`, always with a reason
   in Notes.
3. **Tag the entry point** with `// feature: <id>` (same syntax in Java and C#). A tag in
   `android/src/` counts for the TV and the phone; one in `android/tv/src/` or
   `android/phone/src/` for that one only. `grep -rn "feature: <id>" android windows/src`
   shows every side.
4. **Behaviour with a right answer goes in `spec/cases/*.json`**, not inline in one test file.
   `android/tests/CasesTest.java` and `windows/tests/Tests.cs` both read those files, so one
   edit tests both apps. Only code without Android or WPF dependencies can be tested this way
   (Sun, Dates, Ladder, Sse today; anything new should be written that way where it can).
5. **Before you finish:** `python3 tools/parity.py --diff origin/main`, plus the tests of each app
   you touched (below). A refactor or fix with no user-visible change can skip the ledger with a
   `Parity: none` trailer in the commit message. Otherwise add a trailer like
   `Parity: opt.unread tv=✅ phone=✅ windows=⏳` so `git log --grep Parity:` shows the history.
6. **Owed ports are work.** When you start on a front end, run `python3 tools/parity.py`; any
   `owed` line for it is a candidate for the same session.
7. **The TV UI doesn't change by accident.** Phone work goes in `android/phone/src/` or behind
   the touch flag in shared code; check a change to shared code on the TV too.

## Build and test

```sh
cd android
./test.sh                        # shared cases (../spec/cases) on the JVM
./build.sh                       # build/broadsheet.apk, signed with ../release.keystore
```

```bat
:: Windows port (on Windows; needs ..\assets\fonts, ..\spec and ..\version.env next to windows\)
windows\build.cmd test
```

CI (`.github/workflows/tests.yml`) runs the parity check, the Java cases, an APK build with a
throwaway key, and `build.cmd test` on a Windows runner.

## Versions and releases

- One version for every port: `VERSION_NAME` in `version.env` (the APK's versionName and the
  exe's file version); `VERSION_CODE` is Android's and must go up for every APK that goes on a
  device. Keep the file free of quotes and commas (MSBuild reads it).
- **Release:** write the `## X.Y.Z` section in `CHANGELOG.md` (what a user notices, per port),
  commit and push it, then `tools/release.sh X.Y.Z` on an up-to-date, clean `main`. It checks
  parity and the cases, bumps `version.env`, commits "Release X.Y.Z", tags `vX.Y.Z` and pushes.
  `.github/workflows/release.yml` then tests, builds the signed APK (key from the Actions
  secrets `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD`, certificate checked against
  `android/signing-cert.sha256`), builds and zips the Windows exe, and publishes the release
  with `broadsheet.apk`, `broadsheet-windows.zip` and `SHA256SUMS.txt`.
  `gh workflow run release.yml` is a dry run (artifacts, no release).
- **Signing key:** `release.keystore` at the repo root, git-ignored. Never delete, regenerate or
  commit it. An APK signed with another key can't update an installed Broadsheet.
  `build.sh` refuses to make a new key when `CI` is set or `KEYSTORE=` names a missing file.

## Conventions

- Mirror file and type names across the apps where it makes sense (`Front.java` ↔ `Front.cs`).
- Keep the Windows port buildable with what Windows ships: C# 5 (no `$""`, no `?.`, no
  `nameof`), no NuGet. Keep the Android app free of libraries, Java 8 source, `-Werror` clean.
- The owner's look, on every front end: square and blocky (no rounded corners on the phone),
  rail-style highlights (a lit block with an accent bar at its edge), monochrome drawn icons,
  readable text over pictures, Newsreader serif, light by day and dark after sunset.
- `README.md` is the hub (what it is, downloads, setup, layout, releases, FAQ; written for
  people searching for Apple News on Android, Fire TV and Windows); `android/README.md` and
  `windows/README.md` describe each port. Update the ones you changed, and `CHANGELOG.md`.
