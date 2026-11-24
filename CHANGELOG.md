# Changelog

One version for every port since 0.3.0: the Android app (TV and phone, one APK) and the
Windows port. Each `## X.Y.Z` section is the release notes of the GitHub release `vX.Y.Z`
(`tools/release.sh` refuses to release a version without one). Earlier Windows versions had
their own numbers; they're noted where they shipped.

## 0.3.0

2026-11-23. Needs Apple News Relay 0.3 for the new calls; a 0.2 relay gets the old ones.
First release on GitHub: `broadsheet.apk` (Android TV, Fire TV, Google TV) and
`broadsheet-windows.zip` (Windows 10/11).

- **Deep sections:** Today's stories first, then more from the topic's or publisher's own feed
  (Tech: 3 → 60 stories); the next 15 load as you near the end.
- **A live page:** the front page follows the Mac over the relay's event stream, once the
  remote (or keyboard and mouse) has been still for 8 s, or when the story being read closes.
  Without the stream, a check every 10 minutes.
- **Front page in one call** with `If-None-Match`: a reload that finds nothing new changes
  nothing on screen. The cached page is read off the UI thread.
- **Stories fetched ahead:** moving along the rail fetches the pictures of the sections beside
  it and decodes the next hero; resting on a section or a card has the Mac fetch what's around
  it (one interest list per device, the newest replacing the last); reading toward a story's
  end, the next ones.
- **Stories kept on the device** (8 MB, 400 stories, two weeks): they open at once, even while
  the Mac sleeps. Otherwise a story opens in one trip with its first screens; the rest comes as
  you read toward it.
- The options panel's footer doesn't count stories fetched ahead as waiting.
- Fixes: a reload no longer loses a press on the rail or the focused card.
- Windows: this is the port's former 0.2.0 (the TV app's 0.3.0 changes, ported), renumbered so
  every port shares one version. Hovering a section on the rail counts as resting on it;
  minimising stops the event stream and drops the interest list. The exe's version now comes
  from `version.env`, and `install.ps1` also installs an exe that sits next to it (the release
  zip).
- Repository: renamed `broadsheet` (was `broadsheet-news-tv`) and sorted into ports:
  `android/`, `windows/`, shared `assets/`, `spec/`, `tools/`. Releases are built and signed
  by GitHub Actions.

## 0.2.2

2026-11-23. TV.

- Options: **Mark as unread** (or as read), so a story read on the TV stops being dimmed or
  hidden. A story marked unread while open stays unread when the reader closes.
- Fix: opening a story counted the story the reader last showed as read again.
- The video's Play label is square like the rest. New screenshots.
- Windows 0.1.0 (same day): the first Windows port. One `Broadsheet.exe` on the in-box .NET
  Framework 4.8, built with the in-box MSBuild 4.0 and C# 5: front page, reader and options as
  on the TV, plus mouse and touchpad (browser-like wheel, 1:1 precision touchpads), ← and ☰
  buttons, Open in the browser, and a per-user `install.ps1`.

## 0.2.1

2026-11-23. TV.

- A band at the bottom of the reader says what the Mac is doing while a story is on its way,
  with the seconds, a running bar and the press room at work; grey lines wait where the text
  will go. It stops animating when the story arrives.
- The focused story, and the next one while reading, are fetched ahead.
- Text size is a slider of ten steps, 14 to 40 pt; the column widens for big type.

## 0.2.0

2026-11-23. TV.

- **Options panel** (Menu): save to Saved Stories on the Mac, like or dislike, more from the
  publisher, a topic or related stories, text size, appearance, read stories (show, dim,
  hide), continue reading, refresh now and how often, and the Mac's state in the footer.
- **On the Mac:** the story open in Apple News on the Mac tops the panel; OK carries on with it.
- **Video** in stories plays full screen.
- **Light by day, dark at night**, worked out offline from the time zone.
- Hero cards set their words on dark blocks where the picture is too light under them.
- Stories reopen where they were left; what was read stays on the TV.

## 0.1.4

2026-11-23. TV.

- Finds the relay without Bonjour: after 1.5 s it also asks port 8787 on the TV's own subnet.
  With nothing on screen it looks again every 30 s; OK tries at once.
- Fixes: reader pictures, News+ reading time, brief summaries end on a whole line, the News+
  badge no longer overlaps the dateline, dates older than a week read "Sep 5".

## 0.1.0

2026-11-23. TV. The first version: Apple News on Android TV by way of Apple News Relay.

- Front page with a rail of sections, a hero, briefs and picture cards; a serif reader paged
  with the D-pad; Bonjour discovery of the relay; a cached front page; bodies fetched from
  Apple News on the Mac on demand; Apple News+ previews marked.
