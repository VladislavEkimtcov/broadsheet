# Changelog

One version for every port since 0.3.0: the Android app (TV and phone, one APK) and the
Windows port. Each `## X.Y.Z` section is the release notes of the GitHub release `vX.Y.Z`
(`tools/release.sh` refuses to release a version without one). Earlier Windows versions had
their own numbers; they're noted where they shipped.

## Unreleased

- Windows: two-finger swipes on a precision touchpad, read the way Chrome reads them, and
  animated under your fingers. Fingers from the right of the pad to the left go back, as
  Backspace does: the story (or video) slides away with them and uncovers the front page,
  Options slides back out, a pop-up fades; on the front page the page gives a little and focus
  steps up the rail. Left to right draws Options in from the right edge. Move back and forth to
  rewind and advance; lift past half way, or flick, to finish, or lift early to spring back. A
  diagonal scroll of the page doesn't count. If your touchpad's scrolling direction is reversed
  in Windows, the swipes reverse with it, as in Chrome.
- Windows: `install.ps1 -Persistent` (or `--persistent`) installs Broadsheet as a scheduled task
  named Broadsheet that starts it now and at every sign-in. Running install.ps1 again after a
  build stops it, copies the new exe and starts it again on the desktop, also from an ssh
  session; `schtasks /run /tn Broadsheet` starts it too. `-Uninstall` removes the task.
- TV and Windows: **Relay address** is the last entry in Options, as on the phone. It shows the
  address set by hand or "Automatic" (with where it was found). On the TV, OK opens a box with
  the on-screen keyboard; on Windows, Enter or a click opens a pop-up with a text box. Empty goes
  back to finding the relay by itself; "10.0.0.4" is read as http://10.0.0.4:8787. It writes the
  same relay.conf the app already read.
- Windows: Refresh now's ‹ › stay put as the value changes. They were placed around the value
  itself, so going from "25 min" to "Off" moved them out from under the mouse; they now sit
  around the widest value, with the value centred between them.
- More on …, all ports: Apple News's own bookkeeping tags no longer show up as topics ("More on
  Internal: Spinach", "More on Qualitative: Business, short shelf life", "LLM Politics", "NewsS",
  "… EdOps"). In 54 stories on the relay, "Internal: Spinach" was on 35 of them.
- Phone: Stories you've read has its ‹ › side by side; they had an empty gap between them where
  the value used to be.
- Windows: the masthead, section titles, the Options heading and the reader's headline are in
  Newsreader Display again, as on the TV and phone. That cut names its family "Newsreader
  72pt", so asking for "Newsreader" quietly fell back to Segoe UI; each font is now asked for by
  the name in its own file.
- Options, all ports: **More stories like this ›** replaces the tile that stepped through More
  from / More on / Related. OK (or Right, a tap, a click) opens a pop-up in the middle of the
  screen with every choice and how many stories it has; Back, Left or a tap beside it closes it.
  The lists, their first pictures and the first stories' openings are fetched as soon as
  Options opens, so a pick shows its section at once.
- Windows: Refresh now has ‹ › buttons around how often (25 min, Hourly, Off), like the phone;
  ◀ ▶ still step it, and a click anywhere else on the tile refreshes.
- Options, all ports: shorter. "Save story" reads **Save story on Mac** (**Unsave story on Mac**
  once saved); "Appearance" is now **Dark mode**. The explanations under Save, Like or dislike,
  Mark as read and Open in the browser are gone (errors still show there). More from says "OK
  to show" (TV), "Tap to show" (phone), "Enter to show" (Windows). Stories you've read shows its
  setting underneath (Do nothing, Dim after reading, Hide after reading) instead of a count.
- Windows: the exe carries the red "B" icon, so the Start menu shortcut, the taskbar pin and
  Explorer show it (they showed the generic program icon; only the window had it).
- Windows: the mouse wheel always scrolls the Options panel; it no longer changes the text size,
  Dark mode, Stories you've read or the Like / Dislike choice on the way past. ◀ ▶ still change
  them, and a click on Dark mode or Stories you've read steps to the next value, round to the
  first.
- TV: Back on the front page no longer closes Broadsheet straight away. From a story it goes
  to the section on the rail, then to the rail's top entry; only Back there leaves the app.
- Windows: Backspace, Esc and the mouse's back button do the same on the front page (story,
  then its section on the rail, then the top entry); the window stays open.
- Windows: **videos play inside the app**, HLS included (30 of the last 31 videos in the relay were
  HLS). Windows' own player (WinRT `MediaPlayer`, `AdaptiveMediaSource` for HLS) replaces WPF's
  MediaElement, which couldn't open HLS and crashed on Windows 10 IoT LTSC. Still nothing to
  install and no libraries.
- Windows: `install.ps1` waits for a running Broadsheet to close before copying the new exe
  (it could fail with "file in use" when updating over a running copy).

## 0.4.0

2026-11-23. **Apple News on Android phones.** The same `broadsheet.apk` now runs on phones and
tablets with a touch UI; on a TV it looks and works as before.

- **Phone front page:** a masthead with ☰, the sections as a strip along the top (the TV's
  rail on its side, with the same square highlight), then the section in one column: the hero
  (with the contrast blocks), two briefs and picture cards; two columns where the screen is
  wide. Deep sections load more as you scroll. Pull down to refresh. The cached page shows at
  once on a cold start; when the Mac isn't answering, a plain note says so and the page stays
  readable (tap to try again).
- **Phone reader:** the same serif column and typesetting, scrolled and flung with a finger;
  swipe sideways for the previous or next story; ← and ☰ in a bar on top. The loading band,
  stories fetched ahead, kept stories, the rest loading as you read, reopening where you left
  off and the text size all work as on the TV. A phone starts at 17 pt.
- **Video:** a tap plays it full screen without the system bars; tap to pause, tap the line to
  seek, ← or Back to close.
- **Options** as a sheet from the bottom (☰, or a long press on a card): everything the TV
  has, plus **Open in the browser** and a **Relay address** field (empty: found by itself;
  it writes the same `relay.conf` the TV reads).
- System Back closes the video, the options and the story; the page runs under the status and
  gesture bars, with their icons light or dark to match; rotating keeps your place.
- What's on screen or being read tells the Mac what to fetch ahead, as on the TV; the list is
  dropped when the app goes to the background.
- TV: no visible change. The landscape lock moved from the manifest into code (TV only). Android
  TV, Fire TV and Google TV keep the single launcher entry.
- Windows: no change besides the version.

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
