# Broadsheet for Windows

The TV app's front page, reader and options panel, ported to a Windows desktop app. It reads
Apple News through [Apple News Relay](https://github.com/VladislavEkimtcov/apple-news-relay) on
a Mac, like the TV app.

It's built for minimal Windows installs such as Windows 10/11 IoT Enterprise LTSC: one
self-contained `Broadsheet.exe` (about 900 KB with the Newsreader fonts compiled in) on the
.NET Framework 4.8 that ships with Windows. Installing needs no admin rights, no runtime and no
installer.

## Build (on the Windows machine itself, nothing to install)

```bat
build.cmd          :: bin\Broadsheet.exe, with the in-box MSBuild 4.0 and C# 5 compiler
build.cmd test     :: also builds and runs tests\Tests.cs (a console runner; no NuGet)
powershell -ExecutionPolicy Bypass -File install.ps1 [-Autostart] [-Uninstall]
```

Warning MSB3644 (no reference assemblies) is expected: MSBuild falls back to the GAC. The build
needs `..\assets\fonts`, so copy this folder together with `assets/`.

## Use

- **Keyboard:** the TV's D-pad on the arrow keys. On the front page the arrows move between the
  rail and the cards and Enter opens a story. In the reader, Up/Down glide half a screen,
  Space/Page Down move a screen, Left/Right go to the previous/next story, and Esc or Backspace
  goes back. M or the Menu key opens Options. F5 refreshes, F11 toggles full screen.
- **Mouse and touchpad:** hovering focuses a card and clicking opens it. The wheel scrolls like a
  browser: notched wheels glide, and precision touchpads track your fingers 1:1. In a story, the
  ← at the top left goes back, and the ☰ at the top right (or a right-click) slides out Options.
  The mouse's back button also works.
- **Options** are the TV's: save, like or dislike, mark as unread, more from the publisher or
  topic, text size, appearance (light by day and dark after sunset, worked out from the time
  zone), read stories, continue reading, and refresh. Windows adds "Open in the browser".
- **Video:** plays inside the app with MediaElement where Windows' media stack can. When it can't
  (HLS streams, or images where MediaElement fails), Enter opens the video in the web browser.

## Where things are

| | |
|---|---|
| `%APPDATA%\Broadsheet\settings.conf` | settings (key=value) |
| `%APPDATA%\Broadsheet\library.json` | what you've read and how far, likes; stays on this machine |
| `%APPDATA%\Broadsheet\relay.conf` | optional: `url=http://<mac>:8787` and `token=...` |
| `%LOCALAPPDATA%\Broadsheet\` | the last front page, pictures (pruned to 150 MB), `broadsheet.log` |

It finds the relay by asking port 8787 on its own subnets (Windows has no Bonjour built in),
then remembers the address.

## Code

C# 5 and WPF, all in `src/`, hand-drawn like the TV app: one element per card, pictures
decoded at the size they're shown, and nothing animating at rest. The Windows-only parts and
how they differ from the TV app are listed at the top of `src/MainWindow.cs`.
