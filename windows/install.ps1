# Installs Broadsheet for the current user, no admin: copies Broadsheet.exe (from next to this
# script, as in the release zip, or bin\ after build.cmd) to %LOCALAPPDATA%\Programs\Broadsheet
# and adds a Start menu shortcut.
#   powershell -ExecutionPolicy Bypass -File install.ps1
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Autostart  also start it at sign-in (HKCU Run key)
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Persistent (or --persistent)
#       run it as a scheduled task "Broadsheet": starts now and at sign-in (closing it closes it).
#       Running install.ps1 again (after build.cmd) stops it, copies the new exe and relaunches it
#       on the desktop, also from an ssh session; so does `schtasks /run /tn Broadsheet`.
#   powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall  remove it (settings in %APPDATA% stay)
param([switch]$Autostart, [switch]$Persistent, [switch]$Uninstall)
$ErrorActionPreference = 'Stop'
if ($args -contains '--persistent') { $Persistent = $true }
if ($args -contains '--uninstall') { $Uninstall = $true }
$task = 'Broadsheet'
$hasTask = [bool](Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue)
$dir = Join-Path $env:LOCALAPPDATA 'Programs\Broadsheet'
$exe = Join-Path $dir 'Broadsheet.exe'
$lnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'Broadsheet.lnk'
$run = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
# Stop a running copy and wait until it has let go of the exe (Stop-Process returns before that).
$running = @(Get-Process Broadsheet -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe })
$running | Stop-Process -Force
$running | ForEach-Object { $_.WaitForExit(10000) | Out-Null }
if ($hasTask) { Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue }
if ($Uninstall) {
  if ($hasTask) { Unregister-ScheduledTask -TaskName $task -Confirm:$false }
  Remove-ItemProperty -Path $run -Name Broadsheet -ErrorAction SilentlyContinue
  Remove-Item $lnk, $dir -Recurse -Force -ErrorAction SilentlyContinue
  'Removed.'
  return
}
$src = Join-Path $PSScriptRoot 'Broadsheet.exe'
if (-not (Test-Path $src)) { $src = Join-Path $PSScriptRoot 'bin\Broadsheet.exe' }
if (-not (Test-Path $src)) { throw "No Broadsheet.exe next to install.ps1 or in bin\: run build.cmd first." }
# A zip downloaded with a browser marks its files as from the internet; the copy shouldn't be.
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item $src $exe -Force
Unblock-File $exe -ErrorAction SilentlyContinue
$s = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
$s.TargetPath = $exe
$s.WorkingDirectory = $dir
$s.IconLocation = "$exe,0"
$s.Description = 'Apple News, by way of Apple News Relay on a Mac'
$s.Save()
if ($Persistent) {
  # The task owns starting it now; the Run key would start a second copy at sign-in.
  Remove-ItemProperty -Path $run -Name Broadsheet -ErrorAction SilentlyContinue
  $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
  $action = New-ScheduledTaskAction -Execute $exe -WorkingDirectory $dir
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
  # Interactive: on the signed-in desktop, so a start from ssh shows up on screen too.
  $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
  $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
  Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Description 'Broadsheet: starts at sign-in; install.ps1 relaunches it' -Force | Out-Null
  $hasTask = $true
} elseif ($Autostart) { Set-ItemProperty -Path $run -Name Broadsheet -Value "`"$exe`"" }
"Installed Broadsheet $((Get-Item $exe).VersionInfo.ProductVersion) at $exe ($([math]::Round((Get-Item $exe).Length / 1KB)) KB) and a Start menu shortcut."
if ($hasTask) {
  Start-ScheduledTask -TaskName $task
  "Started task $task (runs at sign-in; run install.ps1 again to relaunch a new build)."
}
