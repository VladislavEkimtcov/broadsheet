@echo off
rem Builds bin\Broadsheet.exe with what Windows ships: MSBuild 4.0 and the C# 5 compiler of
rem .NET Framework 4.8. No SDK, no Visual Studio, no NuGet. Warning MSB3644 (no reference
rem assemblies) is expected: MSBuild falls back to the GAC and the build succeeds.
rem   build.cmd        build the app
rem   build.cmd test   build and run the tests (tests\Tests.cs, a console runner)
setlocal
cd /d "%~dp0"
set FW=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319
if not exist "%FW%\MSBuild.exe" set FW=%WINDIR%\Microsoft.NET\Framework\v4.0.30319
"%FW%\MSBuild.exe" Broadsheet.csproj /nologo /v:minimal /p:Configuration=Release || exit /b 1
if /i not "%1"=="test" exit /b 0
set WPF=%FW%\WPF
"%FW%\csc.exe" /nologo /out:bin\Tests.exe /target:exe /r:"%WPF%\WindowsBase.dll" /r:"%WPF%\PresentationCore.dll" /r:"%FW%\System.Xaml.dll" ^
  tests\Tests.cs src\Json.cs src\Dates.cs src\Sun.cs src\Model.cs src\Ui.cs src\Ladder.cs src\Sse.cs || exit /b 1
bin\Tests.exe
