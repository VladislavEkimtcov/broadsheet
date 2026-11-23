# Agent instructions: broadsheet-news-tv

Two apps, one repo, no shared code: the Android TV app in `src/` (Java, bare Android SDK, no
Gradle) and the Windows port in `windows/` (C# 5, WPF, .NET Framework 4.8 in-box toolchain).
Keep them at feature parity through [PARITY.md](PARITY.md).

## Parity rules

1. **Any user-visible change updates `PARITY.md` in the same commit.** New feature: add a row
   with a new stable ID. Changed feature: check the row still says what each app does.
2. **Port it, or mark it owed.** If you change only one app, set the other app's cell to
   `⏳ <version it appeared in>`. Deliberate differences are `⚠️` or `—`, always with a reason in
   Notes.
3. **Tag the entry point** in each app's code with `// feature: <id>` (same syntax in Java and C#).
   To port a feature, `grep -rn "feature: <id>" src windows/src` shows both sides.
4. **Behaviour with a right answer goes in `spec/cases/*.json`**, not inline in one test file.
   `tests/CasesTest.java` and `windows/tests/Tests.cs` both read those files, so one edit tests
   both apps. Only code without Android or WPF dependencies can be tested this way (Sun, Dates
   today; anything new should be written that way where it can).
5. **Before you finish:** `python3 tools/parity.py --diff origin/main`, plus the tests of each app
   you touched (below). A refactor or fix with no user-visible change can skip the ledger with a
   `Parity: none` trailer in the commit message. Otherwise add a trailer like
   `Parity: opt.unread tv=✅ windows=⏳` so `git log --grep Parity:` shows the history.
6. **Owed ports are work.** When you start on one app, run `python3 tools/parity.py`; any `owed`
   line for that app is a candidate for the same session.

## Build and test

```sh
# TV app (macOS/Linux): shared cases on the JVM, then the APK
P=src/io/github/vladislavekimtcov/broadsheet
javac -d build/test $P/Sun.java $P/Dates.java $P/Ladder.java $P/Sse.java tests/CasesTest.java && java -cp build/test io.github.vladislavekimtcov.broadsheet.CasesTest
./build.sh                       # bump version.env for every APK that goes on a device
```

```bat
:: Windows port (on Windows; needs ..\assets\fonts and ..\spec next to windows\)
windows\build.cmd test
```

CI (`.github/workflows/tests.yml`) runs the parity check, the Java cases, and `build.cmd test`
on a Windows runner.

## Conventions

- Mirror file and type names across the apps where it makes sense (`Front.java` ↔ `Front.cs`).
- Keep the Windows port buildable with what Windows ships: C# 5 (no `$""`, no `?.`, no
  `nameof`), no NuGet. Keep the TV app free of libraries.
- README.md describes the TV app; `windows/README.md` the port. Update the one you changed.
