# Broadsheet for Windows: Apple News on Windows

**Read Apple News on a Windows PC.** Broadsheet's front page, reader and options panel as a
Windows desktop app for Windows 10 and 11. It reads Apple News through
[Apple News Relay](https://github.com/VladislavEkimtcov/apple-news-relay) on a Mac on the same
network, like the Android app. Not affiliated with Apple.

**[Download broadsheet-windows.zip](https://github.com/VladislavEkimtcov/broadsheet/releases/latest/download/broadsheet-windows.zip)**
(the latest release): unzip (if Windows blocked the download: right-click the zip → Properties →
Unblock first), then `powershell -ExecutionPolicy Bypass -File install.ps1`. If SmartScreen
stops `Broadsheet.exe`, choose *More info* → *Run anyway*.

It's built for minimal Windows installs such as Windows 10/11 IoT Enterprise LTSC: one
self-contained `Broadsheet.exe` (about 900 KB with the Newsreader fonts compiled in) on the
.NET Framework 4.8 that ships with Windows. Installing needs no admin rights, no runtime and no
installer.

## Build (on the Windows machine itself, nothing to install)

```bat
build.cmd          :: bin\Broadsheet.exe, with the in-box MSBuild 4.0 and C# 5 compiler
build.cmd test     :: also builds and runs tests\Tests.cs (a console runner; no NuGet)
powershell -ExecutionPolicy Bypass -File install.ps1 [-Autostart | -Persistent] [-Uninstall]
```

Warning MSB3644 (no reference assemblies) is expected: MSBuild falls back to the GAC. The build
needs `..\assets\fonts` and `..\version.env` (the version every port shares; it becomes the
exe's file version), and the tests read `..\spec\cases`, so copy this folder together with
`assets/`, `spec/` and `version.env`. `install.ps1` takes `Broadsheet.exe` from next to it (the
release zip) or from `bin\`.

While developing, install once with `-Persistent`; after that, `build.cmd && powershell
-ExecutionPolicy Bypass -File install.ps1` relaunches the new build on the desktop, also over ssh.

## Use

- **Keyboard:** the TV's D-pad on the arrow keys. On the front page the arrows move between the
  rail and the cards and Enter opens a story; Esc or Backspace goes from a story to its section on the rail, then to
  the rail's top entry. In the reader, Up/Down glide half a screen,
  Space/Page Down move a screen, Left/Right go to the previous/next story, and Esc or Backspace
  goes back. M or the Menu key opens Options. F5 refreshes, F11 toggles full screen.
- **Mouse and touchpad:** hovering focuses a card and clicking opens it. The wheel scrolls like a
  browser: notched wheels glide, and precision touchpads track your fingers 1:1. In a story, the
  ← at the top left goes back, and the ☰ at the top right (or a right-click) slides out Options.
  The mouse's back button also works. **Two-finger swipes** on a precision touchpad work as in
  Chrome: right to left goes back (what Backspace does), left to right opens Options.
- **Options** are the TV's: save, like or dislike, mark as unread, more from the publisher or
  topic, text size, dark mode (light by day and dark after sunset, worked out from the time
  zone), read stories, continue reading, and refresh. Windows adds "Open in the browser". More stories like this › opens a pop-up
  of choices; Refresh now has ‹ › buttons for how often. The wheel
  always scrolls the panel and never changes a setting: ◀ ▶ do that, a click on the text size
  track moves the knob there, and a click on Dark mode or Stories you've read goes to the next
  value (round to the first after the last).
- **Video:** plays inside the app, full window, with Windows' own player (the one behind the
  Films & TV app, in the box since Windows 10 1809): HLS streams, which most publishers send, and
  MP4. Space pauses, Left/Right skip 10 s, Page Up/Down 30 s, clicking the line seeks, Esc goes
  back. If a video still won't play, Enter opens it in the web browser.

## Where things are

| | |
|---|---|
| `%APPDATA%\Broadsheet\settings.conf` | settings (key=value) |
| `%APPDATA%\Broadsheet\library.json` | what you've read and how far, likes; stays on this machine |
| `%APPDATA%\Broadsheet\relay.conf` | optional: `url=http://<mac>:8787` and `token=...`; Options → Relay address writes it |
| `%LOCALAPPDATA%\Broadsheet\` | the last front page, pictures (`img`, 150 MB, least used out first), `broadsheet.log` (startup timings, `front 304`, `open … ms kept here`) |
| `%LOCALAPPDATA%\Broadsheet\bodies` | stories opened or headed for (8 MB, 400 stories, two weeks), so they open at once, even while the Mac sleeps |

It finds the relay by asking port 8787 on its own subnets (Windows has no Bonjour built in),
then remembers the address. With relay 0.3 it talks to it the way the TV app does (see the Android
README's "How it talks to the relay"): the front page in one call with `If-None-Match`, the
relay's event stream for changes (shown once keys, wheel and clicks have been still for 8 s),
deep sections a page at a time, stories in one trip with the rest as you read, and an interest
list of the stories you're heading for (the card in focus or under the pointer and its
neighbours, a section's first three when you rest on it or hover it on the rail). Minimised, it
stops the stream and drops its list.

## Code

C# 5 and WPF, all in `src/`, hand-drawn like the TV app: one element per card, pictures
decoded at the size they're shown, and nothing animating at rest. The Windows-only parts and
how they differ from the TV app are listed in [PARITY.md](../PARITY.md), feature by feature; the code
is tagged `// feature: <id>` on both sides.
