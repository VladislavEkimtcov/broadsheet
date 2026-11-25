# Feature parity: TV, phone and Windows

Broadsheet has three front ends in two apps that share no code. The Android app (Java, Android
SDK, no Gradle) is one APK for TVs and phones: `android/src/` is shared by both (relay client,
controller, reader, options), `android/tv/src/` is the TV's own UI and `android/phone/src/` the
phone's. The Windows port is `windows/src/` (C# 5, WPF, .NET Framework 4.8 in-box). This file is
the list of what each one does. `tools/parity.py` checks it against the code, and CI runs that
check.

**How it works**

- Every user-visible feature has a row and a stable ID (`opt.unread`). IDs never change, so
  `git log -S"feature: opt.unread"` and `grep -rn "feature: opt.unread"` keep working.
- The code marks where each feature starts with a comment: `// feature: opt.unread` (the same
  syntax in Java and C#; several IDs: `// feature: a, b`). Grep the ID to find the code on the
  other side. A tag in `android/src/` counts for the TV and the phone, since both run that code;
  a tag in `android/tv/src/` or `android/phone/src/` counts for that one only.
- Behaviour with a right answer (dates, sunrise, parsing) lives in [`spec/cases/`](spec/cases/)
  as JSON, and both test suites read the same files: `android/tests/CasesTest.java` and
  `windows/tests/Tests.cs`. Change a case and both platforms must agree again.

**Statuses**

| | |
|---|---|
| ✅ | Done. The code must carry the ID's tag. Version where it shipped, optionally. |
| ⏳ | Owed: another platform has it, this one doesn't yet. Note the version it appeared in. |
| ⚠️ | Divergent on purpose. Notes must say why. |
| — | Doesn't apply to this platform. Notes must say why. |

**When you change one front end** (rules for people and agents, also in [AGENTS.md](AGENTS.md)):
add or update the row in the same commit. Port the change, or mark the others ⏳.
`tools/parity.py --diff origin/main` fails when some front ends' code changed, others' didn't and
this file didn't, unless a commit in the range has a `Parity: none` trailer (refactors and fixes
that change nothing a user sees).

Versions at last full sync: 0.4.0 for every port (TV and phone share the APK; the phone's touch
UI arrived in 0.4.0), 2026-11-23. Before that, 0.3.0 (Windows 0.3.0 is the former 0.2.0,
renumbered when the ports started sharing one version).

## Front page

| ID | Feature | TV | Phone | Windows | Notes |
|---|---|---|---|---|---|
| `front.rail` | Rail of sections on the left; hero, two briefs, rows of picture cards; skimming the rail | ✅ | ⚠️ 0.4.0 | ✅ | Phone: the sections as a strip along the top, the section in one column (two where wide); see `phone.strip`, `phone.feed` |
| `front.cache` | Last front page shows at once from cache (read off the UI thread), then is checked with the Mac: nothing comes back when it didn't change (304) | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 |  |
| `front.live` | The page follows the Mac as it changes (the relay's event stream), once the remote has been still for 8 s; without the stream, a check every 10 minutes | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | A change that comes while reading shows when the story closes |
| `front.back` | Back steps out of the front page: from a story to its section on the rail, then to the rail's top entry, then out of the app | ✅ | — | ✅ | One step per press (a held key takes one). Windows: Backspace, Esc, the mouse's back button; at the top entry the window stays open (Esc still leaves full screen first). Phone: no rail; Back leaves from the front page (`phone.back`) |
| `front.deep` | Sections run deep: Apple News Today's stories first, then more from the topic's or publisher's own feed (or what the section carried today); the next page loads as focus nears the end | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | Composed by the relay (0.3); a 0.2 relay gives Today's stories only |
| `front.warm` | Moving along the rail readies the sections beside it (pictures fetched ahead, the next hero decoded); resting on a section has the Mac fetch its first stories | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | Windows: hovering a section counts as resting on it. Phone: choosing a section on the strip counts as resting on it |
| `front.hero_contrast` | Hero's words get dark blocks where the picture under them is too light (WCAG contrast) | ✅ | ✅ 0.4.0 | ✅ |  |
| `dates.labels` | "12m ago", "Yesterday", "Sep 5", reading time | ✅ | ✅ 0.4.0 | ✅ | Cases: `spec/cases/dates.json` |

## Reader

| ID | Feature | TV | Phone | Windows | Notes |
|---|---|---|---|---|---|
| `reader.column` | Single serif column: headline, standfirst, pictures with captions, quotes, subheads | ✅ | ✅ 0.4.0 | ✅ | Phone: as wide as the screen allows, under a bar with ← and ☰ |
| `reader.loading_band` | Band while the Mac fetches the body: running bar, "Inking the plates…" | ✅ | ✅ 0.4.0 | ✅ |  |
| `reader.prefetch` | Resting on a card (0.3 s) has the Mac fetch it and the cards around it; a story that arrived, the next one; nearing a story's end, the next two and the previous. One list per device at the relay, the newest replacing the last | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | Cases: `spec/cases/ladder.json`. A 0.2 relay gets one background fetch at a time. Phone: the card nearest the top of the screen once the page stops counts as resting on it |
| `reader.body_cache` | Stories opened or headed for are kept on the device (8 MB, 400 stories, two weeks), so they open at once, even while the Mac sleeps | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | TV: app cache folder. Windows: `%LOCALAPPDATA%\Broadsheet\bodies` |
| `reader.progressive` | A story opens in one trip with its first couple of screens; the rest loads as you read toward it | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | Needs relay 0.3 |
| `reader.resume` | Reopening a story goes back to where you left it | ✅ | ✅ 0.4.0 | ✅ |  |
| `reader.prev_next` | Left/Right: previous/next story, animated as a card stack | ✅ | ⚠️ 0.4.0 | ✅ | Next story lands on top from the right; previous story is uncovered as the top card slides off right (edge bounce at ends). Phone: also a live sideways swipe (`phone.swipe`) |
| `reader.page_keys` | A page at a time | ✅ | — | ✅ | TV: Fast-forward/Rewind. Windows: Space/Page Down/Page Up. Phone: no page keys; it scrolls and flings with a finger |
| `win.paragraphs` | Paragraphs split on line breaks in the relay's text | — | — | ✅ | Windows only, on the owner's request (was in MainWindow.cs's divergence note) |
| `video.inline` | A story's video plays inside the app, full screen; Left/Right 10 s, OK pause | ✅ | ✅ 0.4.0 | ⚠️ | Windows: Windows' own WinRT MediaPlayer (HLS via AdaptiveMediaSource, and MP4) since Unreleased, replacing MediaElement (no HLS, crashed on DOORWEDGE's IoT image); stays ⚠️ until checked on screen on DOORWEDGE (decoding verified there headless, sound not yet). Enter opens it in the browser if it still fails. Phone: a tap plays it full screen without the system bars; tap pauses, tap on the line seeks |

## Options panel (☰ Menu)

| ID | Feature | TV | Phone | Windows | Notes |
|---|---|---|---|---|---|
| `opt.panel` | Panel slides in from the right over the front page or a story | ✅ | ⚠️ 0.4.0 | ✅ | Phone: a sheet from the bottom, from ☰ or a long press on a card (`phone.sheet`) |
| `opt.handoff` | On the Mac: carry on with the story open in Apple News on the Mac | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.save` | Save story on Mac / Unsave story on Mac (Saved Stories) | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.unread` | Mark as unread / as read | ✅ 0.2.2 | ✅ 0.4.0 | ✅ 0.1.0 |  |
| `opt.react` | Like or dislike (Suggest More/Fewer); OK again takes it back | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.more_from` | More from the publisher / More on a topic, as a new rail section | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.related` | Related stories | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.more_menu` | "More stories like this ›" opens a pop-up in the middle with the More from / More on / Related choices, each with its story count; every list (with its first pictures and openings) fetched as Options opens, so a pick shows at once | ✅ | ✅ | ✅ | TV: Up/Down, OK or Right, Back or Left closes. Phone: tap a row, tap beside it closes. Windows: click or Enter, Esc/Left/click beside closes |
| `opt.text_size` | Text size slider, ten steps, 14–40 pt | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.theme` | Dark mode: automatic, dark, light | ✅ | ✅ 0.4.0 | ✅ |  |
| `theme.sun` | Automatic turns light at sunrise, dark at sunset, from the time zone (USNO equation) | ✅ | ✅ 0.4.0 | ✅ | Cases: `spec/cases/sun.json`. Windows keys places by Windows zone ids |
| `opt.read_mode` | Stories you've read: do nothing, dim or hide after reading | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.continue` | Continue reading the last unfinished story | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.refresh` | Refresh now | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.auto_refresh` | Refresh by itself every 25 min / hour / off | ✅ | ✅ 0.4.0 | ✅ |  |
| `opt.update` | Watch GitHub Releases (`HEAD /releases/latest`), download & verify updates in the background, install gracefully | ✅ | ✅ | ✅ | Cases: `spec/cases/update.json`. Release builds enable background checks (30 s after launch, throttled to 4 h). TV/Phone: non-intrusive notice/banner + Options tile opens Android installer when selected. Windows: stages `Broadsheet.exe.new` and swaps on exit (or Options → Restart to update) |
| `opt.status_footer` | Footer: is the Mac answering, how much it has to do | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | Stories fetched ahead don't count as waiting |
| `win.browser` | Open in the browser | — | ✅ 0.4.0 | ✅ | No browser worth handing off to on the TV. Phone: a browser is at hand there too |

## Relay and storage

| ID | Feature | TV | Phone | Windows | Notes |
|---|---|---|---|---|---|
| `relay.bonjour` | Find the relay by Bonjour (`_newsrelay._tcp`) | ✅ | ✅ 0.4.0 | — | Windows has no Bonjour built in |
| `relay.subnet` | Find the relay by asking port 8787 on its own subnets | ✅ | ✅ 0.4.0 | ✅ |  |
| `relay.conf` | `relay.conf` with `url=` and `token=` points at the relay by hand | ✅ | ✅ 0.4.0 | ✅ | TV: app's files folder. Windows: `%APPDATA%\Broadsheet`. All three: also the Relay address entry in Options (`phone.relay_field`), which writes the same file |
| `relay.events` | Reads the relay's event stream (Server-Sent Events) and reconnects by itself | ✅ 0.3.0 | ✅ 0.4.0 | ✅ 0.2.0 | Cases: `spec/cases/sse.json` |
| `library.local` | What you read, how far, likes: `library.json` on the device only | ✅ | ✅ 0.4.0 | ✅ |  |

## Touch (phone)

| ID | Feature | TV | Phone | Windows | Notes |
|---|---|---|---|---|---|
| `phone.feed` | Front page in one column: masthead with ☰, hero, briefs, picture cards; two columns where the screen is wide | — | ✅ 0.4.0 | — | TV and Windows: the rail and grid (`front.rail`) |
| `phone.strip` | Sections as a strip along the top with a continuous highlight blob that tears and flows between tabs in lockstep with swiping sideways across categories | — | ✅ 0.4.0 | — | TV and Windows: the rail on the left |
| `phone.pull_refresh` | Pull down at the top of the page to refresh (asks the Mac for a fresh Today, then reloads) | — | ✅ 0.4.0 | — | TV and Windows: Refresh now in Options; Windows also F5 |
| `phone.swipe` | Swipe sideways in a story like a card stack: swipe left lands the next story on top, swipe right lifts the current card off the top | — | ✅ 0.4.0 | — | TV: Left/Right. Windows: Left/Right keys (`reader.prev_next`) |
| `phone.long_press` | Long press on a card: Options for that story | — | ✅ 0.4.0 | — | TV: Menu on the focused card. Windows: hover a card and press M |
| `phone.sheet` | Options as a sheet from the bottom: tap a tile, ‹ › for values, drag the text size slider, tap the shade to close | — | ✅ 0.4.0 | — | TV and Windows: the panel from the right (`opt.panel`) |
| `phone.relay_field` | Relay address, the last entry in Options (empty: found by itself); writes relay.conf | ✅ | ✅ 0.4.0 | ✅ | TV: a tile showing the address (or Automatic); OK opens a box with the on-screen keyboard, Done sets it, Back leaves it. Windows: a tile; Enter or a click opens a pop-up with a text box, Enter sets it, Esc leaves it. Phone: a field under the tiles |
| `phone.insets` | Edge to edge under the status bar and gesture bar, light or dark icons with the page; rotating keeps the place | — | ✅ 0.4.0 | — | TV: full screen, landscape. Windows: a window |
| `phone.back` | System Back closes the video, the options, the story, then leaves the app | — | ✅ 0.4.0 | — | TV: the remote's Back, in each view, and on the front page `front.back`. Windows: Esc, Backspace, the mouse's back button |
| `phone.offline` | "The Mac isn't answering" as a note over the cached page, which stays readable; a tap tries again | — | ✅ 0.4.0 | — | TV and Windows: the rail's status line says it |

## Windows only

| ID | Feature | TV | Phone | Windows | Notes |
|---|---|---|---|---|---|
| `win.mouse` | Hover focuses a card, click opens it; mouse back button | — | — | ✅ | D-pad only on the TV. Phone: touch |
| `win.swipe` | Two-finger touchpad swipe, read as Chrome does (the sideways scroll), followed as the fingers move: right to left sends the story (or video) off to the right, uncovering the page under it, fades the pop-up, or pushes Options out (Back, what Backspace does in each view); left to right draws Options in. Back and forth rewinds and advances it; lifting past half way, or a flick, finishes it, else it springs back. Lift known from the touchpad's own reports (WM_INPUT) | — | — | ✅ | Cases: `spec/cases/swipe.json` (Windows only). D-pad only on the TV. Phone: a sideways swipe is the previous or next story (`phone.swipe`); system Back goes back |
| `win.wheel` | Browser-like wheel; precision touchpads track 1:1; in Options it only scrolls (click cycles a setting) | — | — | ✅ | D-pad only on the TV. Phone: touch |
| `win.icon_buttons` | ← back in the reader, ☰ for Options | — | ✅ 0.4.0 | ✅ | The remote has Back and Menu keys. Phone: ← and ☰ in the reader's top bar, ☰ by the masthead |
| `win.refresh_stepper` | Refresh now: ‹ how often › buttons on the tile (25 min / Hourly / Off), clickable, ◀ ▶ too; a click elsewhere refreshes | — | — | ✅ | The TV steps it with ◀ ▶. Phone: the sheet's ‹ › do this already (`phone.sheet`) |
| `win.keys` | F5 refresh, F11 / Alt+Enter full screen | — | — | ✅ | No such keys on the remote. Phone: no such keys |
