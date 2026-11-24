Broadsheet for Windows: Apple News on a Windows PC
===================================================

Needs Apple News Relay running on a Mac on the same network:
https://github.com/VladislavEkimtcov/apple-news-relay

Install (for this user, no admin rights):
  1. If Windows blocked the download: right-click the zip > Properties > Unblock, then unzip.
  2. Run:  powershell -ExecutionPolicy Bypass -File install.ps1
     (adds Broadsheet to the Start menu; -Autostart also starts it at sign-in;
      -Uninstall removes it)
  Or just run Broadsheet.exe. If SmartScreen says "Windows protected your PC",
  choose "More info", then "Run anyway".

Windows 10 or 11 with .NET Framework 4.8 (built in). More:
https://github.com/VladislavEkimtcov/broadsheet/tree/main/windows
