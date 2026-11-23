# Feature parity: TV app (Java) and Windows port (C#)

Broadsheet has two apps that share no code: the Android TV app in `src/` (Java, Android SDK,
no Gradle) and the Windows port in `windows/src/` (C# 5, WPF, .NET Framework 4.8 in-box). This
file is the list of what each one does. `tools/parity.py` checks it against the code, and CI runs
that check.

**How it works**

- Every user-visible feature has a row and a stable ID (`opt.unread`). IDs never change, so
  `git log -S"feature: opt.unread"` and `grep -rn "feature: opt.unread"` keep working.
- The code marks where each feature starts with a comment: `// feature: opt.unread` (the same
  syntax in Java and C#; several IDs: `// feature: a, b`). Grep the ID to find the code on the
  other side.
- Behaviour with a right answer (dates, sunrise, parsing) lives in [`spec/cases/`](spec/cases/)
  as JSON, and both test suites read the same files: `tests/CasesTest.java` and
  `windows/tests/Tests.cs`. Change a case and both platforms must agree again.

**Statuses**

| | |
|---|---|
| ✅ | Done. The code must carry the ID's tag. Version where it shipped, optionally. |
| ⏳ | Owed: the other platform has it, this one doesn't yet. Note the version it appeared in. |
| ⚠️ | Divergent on purpose. Notes must say why. |
| — | Doesn't apply to this platform. Notes must say why. |

**When you change one app** (rules for people and agents, also in [AGENTS.md](AGENTS.md)):
add or update the row in the same commit. Port the change, or mark the other side ⏳.
`tools/parity.py --diff origin/main` fails when only one app's code changed and this file didn't,
unless a commit in the range has a `Parity: none` trailer (refactors and fixes that change
nothing a user sees).

Versions at the last full sync: TV 0.2.2, Windows 0.1.0 (2026-11-23).

## Front page

| ID | Feature | TV | Windows | Notes |
|---|---|---|---|---|
| `front.rail` | Rail of sections on the left; hero, two briefs, rows of picture cards; skimming the rail | ✅ | ✅ | |
| `front.cache` | Last front page shows at once from cache, then refreshes | ✅ | ✅ | |
| `front.hero_contrast` | Hero's words get dark blocks where the picture under them is too light (WCAG contrast) | ✅ | ✅ | |
| `dates.labels` | "12m ago", "Yesterday", "Sep 5", reading time | ✅ | ✅ | Cases: `spec/cases/dates.json` |

## Reader

| ID | Feature | TV | Windows | Notes |
|---|---|---|---|---|
| `reader.column` | Single serif column: headline, standfirst, pictures with captions, quotes, subheads | ✅ | ✅ | |
| `reader.loading_band` | Band while the Mac fetches the body: running bar, "Inking the plates…" | ✅ | ✅ | |
| `reader.prefetch` | Resting on a card or reading a story has the Mac fetch it or the next one ahead | ✅ | ✅ | |
| `reader.resume` | Reopening a story goes back to where you left it | ✅ | ✅ | |
| `reader.prev_next` | Left/Right: previous/next story | ✅ | ✅ | |
| `reader.page_keys` | A page at a time | ✅ | ✅ | TV: Fast-forward/Rewind. Windows: Space/Page Down/Page Up |
| `win.paragraphs` | Paragraphs split on line breaks in the relay's text | — | ✅ | Windows only, on the owner's request (was in MainWindow.cs's divergence note) |
| `video.inline` | A story's video plays inside the app, full screen; Left/Right 10 s, OK pause | ✅ | ⚠️ | Windows: MediaElement where Windows' media stack can; HLS, or images where MediaElement crashes (DOORWEDGE's IoT image), open in the browser on Enter |

## Options panel (☰ Menu)

| ID | Feature | TV | Windows | Notes |
|---|---|---|---|---|
| `opt.panel` | Panel slides in from the right over the front page or a story | ✅ | ✅ | |
| `opt.handoff` | On the Mac: carry on with the story open in Apple News on the Mac | ✅ | ✅ | |
| `opt.save` | Save story / take it out of Saved Stories on the Mac | ✅ | ✅ | |
| `opt.unread` | Mark as unread / as read | ✅ 0.2.2 | ✅ 0.1.0 | |
| `opt.react` | Like or dislike (Suggest More/Fewer); OK again takes it back | ✅ | ✅ | |
| `opt.more_from` | More from the publisher / More on a topic, as a new rail section | ✅ | ✅ | |
| `opt.related` | Related stories | ✅ | ✅ | |
| `opt.text_size` | Text size slider, ten steps, 14–40 pt | ✅ | ✅ | |
| `opt.theme` | Appearance: automatic, dark, light | ✅ | ✅ | |
| `theme.sun` | Automatic turns light at sunrise, dark at sunset, from the time zone (USNO equation) | ✅ | ✅ | Cases: `spec/cases/sun.json`. Windows keys places by Windows zone ids |
| `opt.read_mode` | Stories you've read: show, dim or hide | ✅ | ✅ | |
| `opt.continue` | Continue reading the last unfinished story | ✅ | ✅ | |
| `opt.refresh` | Refresh now | ✅ | ✅ | |
| `opt.auto_refresh` | Refresh by itself every 25 min / hour / off | ✅ | ✅ | |
| `opt.status_footer` | Footer: is the Mac answering, how much it has to do | ✅ | ✅ | |
| `win.browser` | Open in the browser | — | ✅ | No browser worth handing off to on the TV |

## Relay and storage

| ID | Feature | TV | Windows | Notes |
|---|---|---|---|---|
| `relay.bonjour` | Find the relay by Bonjour (`_newsrelay._tcp`) | ✅ | — | Windows has no Bonjour built in |
| `relay.subnet` | Find the relay by asking port 8787 on its own subnets | ✅ | ✅ | |
| `relay.conf` | `relay.conf` with `url=` and `token=` points at the relay by hand | ✅ | ✅ | TV: app's files folder. Windows: `%APPDATA%\Broadsheet` |
| `library.local` | What you read, how far, likes: `library.json` on the device only | ✅ | ✅ | |

## Windows only

| ID | Feature | TV | Windows | Notes |
|---|---|---|---|---|
| `win.mouse` | Hover focuses a card, click opens it; mouse back button | — | ✅ | D-pad only on the TV |
| `win.wheel` | Browser-like wheel; precision touchpads track 1:1 | — | ✅ | D-pad only on the TV |
| `win.icon_buttons` | ← back in the reader, ☰ for Options | — | ✅ | The remote has Back and Menu keys |
| `win.keys` | F5 refresh, F11 / Alt+Enter full screen | — | ✅ | No such keys on the remote |
