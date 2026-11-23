# Installs Broadsheet for the current user, no admin: copies bin\Broadsheet.exe to
# %LOCALAPPDATA%\Programs\Broadsheet and adds a Start menu shortcut.
#   powershell -ExecutionPolicy Bypass -File install.ps1             build first with build.cmd
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Autostart  also start it at sign-in (HKCU Run key)
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall  remove it (settings in %APPDATA% stay)
param([switch]$Autostart, [switch]$Uninstall)
$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:LOCALAPPDATA 'Programs\Broadsheet'
$exe = Join-Path $dir 'Broadsheet.exe'
$lnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'Broadsheet.lnk'
$run = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
Get-Process Broadsheet -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Stop-Process -Force
if ($Uninstall) {
  Remove-ItemProperty -Path $run -Name Broadsheet -ErrorAction SilentlyContinue
  Remove-Item $lnk, $dir -Recurse -Force -ErrorAction SilentlyContinue
  'Removed.'
  return
}
$src = Join-Path $PSScriptRoot 'bin\Broadsheet.exe'
if (-not (Test-Path $src)) { throw "No ${src}: run build.cmd first." }
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item $src $exe -Force
$s = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
$s.TargetPath = $exe
$s.WorkingDirectory = $dir
$s.Description = 'Apple News, by way of Apple News Relay on a Mac'
$s.Save()
if ($Autostart) { Set-ItemProperty -Path $run -Name Broadsheet -Value "`"$exe`"" }
"Installed $exe ($([math]::Round((Get-Item $exe).Length / 1KB)) KB) and a Start menu shortcut."
