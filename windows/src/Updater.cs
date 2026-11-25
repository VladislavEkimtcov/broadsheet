using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Threading;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;

namespace Broadsheet
{
    enum UpdateState
    {
        Idle,
        Checking,
        Downloading,
        Ready,
        UpToDate,
        AvailableDev,
        Error
    }

    /// <summary>
    /// Watches GitHub Releases gracefully (HEAD /releases/latest without hitting the JSON API
    /// rate limit), stages a verified Broadsheet.exe.new from broadsheet-windows.zip, and swaps
    /// binaries silently on exit (or on demand via Options -> Restart to update).
    ///
    /// Inspired by SleepPicker's AutoUpdater.
    /// </summary>
    static class Updater  // feature: opt.update
    {
        const string LatestReleaseUrl = "https://github.com/VladislavEkimtcov/broadsheet/releases/latest";
        const string AssetDownloadUrlFormat = "https://github.com/VladislavEkimtcov/broadsheet/releases/download/v{0}/broadsheet-windows.zip";
        const string PrefCheckedAt = "update_checked_at";
        const long CheckIntervalMs = 4L * 3600 * 1000;
        const int InitialDelayMs = 30 * 1000;
        const int PeriodicTimerMs = 4 * 3600 * 1000;
        const int MinExeBytes = 32 * 1024;

        public static event Action StatusChanged;

        static Prefs prefs;
        static Dispatcher dispatcher;
        static Timer timer;
        static int busy;
        static int swapApplied;
        static bool started;

        static volatile UpdateState state = UpdateState.Idle;
        static volatile string remoteVersion;
        static volatile string errorText;

        public static bool AutoUpdateEnabled
        {
            get
            {
#if ENABLE_AUTO_UPDATE
                return true;
#else
                return false;
#endif
            }
        }

        public static UpdateState State { get { return state; } }

        public static string RemoteVersion { get { return remoteVersion; } }

        public static string CurrentVersion
        {
            get
            {
                Version v = typeof(Updater).Assembly.GetName().Version;
                return v.Major + "." + v.Minor + "." + v.Build;
            }
        }

        public static string BannerText
        {
            get
            {
                if (state != UpdateState.Ready || remoteVersion == null) return null;
                return "Update v" + remoteVersion + " ready\nUpdates on exit";
            }
        }

        public static string HintText()
        {
            switch (state)
            {
                case UpdateState.Ready:
                    return "Updates automatically on exit  ·  Enter restarts now";
                case UpdateState.Checking:
                    return "Checking GitHub Releases…";
                case UpdateState.Downloading:
                    return "Downloading v" + remoteVersion + "…";
                case UpdateState.UpToDate:
                    return "Up to date  ·  Click or Enter to check";
                case UpdateState.AvailableDev:
                    return "v" + remoteVersion + " on GitHub (auto-update off in dev builds)";
                case UpdateState.Error:
                    return (errorText ?? "Check failed") + "  ·  Click or Enter to retry";
                default:
                    return AutoUpdateEnabled
                        ? "Checks automatically  ·  Click or Enter to check now"
                        : "Dev build  ·  Click or Enter to check";
            }
        }

        public static void Start(Prefs appPrefs, Dispatcher uiDispatcher)
        {
            if (started) return;
            started = true;
            prefs = appPrefs;
            dispatcher = uiDispatcher;
            ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072; // TLS 1.2

            ThreadPool.QueueUserWorkItem(_ =>
            {
                CleanupOldFiles();
                CheckExistingStagedUpdate();
            });

#if ENABLE_AUTO_UPDATE
            SystemEvents.PowerModeChanged += OnPowerModeChanged;
            timer = new Timer(OnTimerTick, null, InitialDelayMs, PeriodicTimerMs);
#endif
        }

        public static void Stop()
        {
#if ENABLE_AUTO_UPDATE
            SystemEvents.PowerModeChanged -= OnPowerModeChanged;
            Timer t = timer;
            timer = null;
            if (t != null) t.Dispose();
#endif
        }

        public static void CheckNow()
        {
            ThreadPool.QueueUserWorkItem(_ => RunCheck(true));
        }

        static void OnTimerTick(object _)
        {
            if (!AutoUpdateEnabled) return;
            long last = prefs != null ? prefs.GetLong(PrefCheckedAt, 0) : 0;
            if (Dates.NowMs() - last < CheckIntervalMs) return;
            RunCheck(false);
        }

#if ENABLE_AUTO_UPDATE
        static void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
        {
            if (e.Mode == PowerModes.Resume) OnTimerTick(null);
        }
#endif

        static string ExePath()
        {
            string loc = Assembly.GetExecutingAssembly().Location;
            if (!string.IsNullOrEmpty(loc)) return loc;
            return Process.GetCurrentProcess().MainModule.FileName;
        }

        static void CleanupOldFiles()
        {
            if (!AutoUpdateEnabled) return;
            string exe = ExePath();
            DeleteQuietly(exe + ".old");
            DeleteQuietly(exe + ".download");
            DeleteQuietly(exe + ".extract");
        }

        static void CheckExistingStagedUpdate()
        {
            if (!AutoUpdateEnabled) return;
            string newPath = ExePath() + ".new";
            if (!File.Exists(newPath)) return;
            string ver = ValidExeVersion(newPath);
            if (ver != null && Updates.IsNewer(ver, CurrentVersion))
            {
                SetState(UpdateState.Ready, ver, null);
            }
            else
            {
                DeleteQuietly(newPath);
            }
        }

        static void RunCheck(bool manual)
        {
            if (!manual && !AutoUpdateEnabled) return;
            if (Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
            try
            {
                if (state == UpdateState.Ready && !manual) return;
                if (manual && state != UpdateState.Ready) SetState(UpdateState.Checking, remoteVersion, null);

                string tag = FetchLatestTag();
                long now = Dates.NowMs();
                if (prefs != null && dispatcher != null)
                    dispatcher.BeginInvoke(new Action(() => prefs.Set(PrefCheckedAt, now)));

                if (tag == null)
                {
                    if (manual && state != UpdateState.Ready) SetState(UpdateState.Error, null, "No release found");
                    return;
                }
                if (!Updates.IsNewer(tag, CurrentVersion))
                {
                    if (AutoUpdateEnabled) DeleteQuietly(ExePath() + ".new");
                    SetState(UpdateState.UpToDate, null, null);
                    return;
                }
                if (!AutoUpdateEnabled)
                {
                    if (manual) SetState(UpdateState.AvailableDev, tag, null);
                    return;
                }

                string exe = ExePath();
                string newPath = exe + ".new";
                if (File.Exists(newPath))
                {
                    string staged = ValidExeVersion(newPath);
                    if (staged != null && !Updates.IsNewer(tag, staged))
                    {
                        SetState(UpdateState.Ready, staged, null);
                        return;
                    }
                    DeleteQuietly(newPath);
                }

                SetState(UpdateState.Downloading, tag, null);
                string downloadPath = exe + ".download";
                string extractPath = exe + ".extract";
                try
                {
                    string url = string.Format(AssetDownloadUrlFormat, tag);
                    using (WebClient wc = new WebClient())
                    {
                        wc.Headers.Add("User-Agent", Net.UserAgent);
                        wc.DownloadFile(url, downloadPath);
                    }
                    ExtractExe(downloadPath, extractPath);
                    string extractedVer = ValidExeVersion(extractPath);
                    if (extractedVer == null || Updates.IsNewer(tag, extractedVer))
                    {
                        if (manual) SetState(UpdateState.Error, null, "Downloaded update failed verification");
                        return;
                    }
                    DeleteQuietly(newPath);
                    File.Move(extractPath, newPath);
                    Paths.Log("update: staged v" + extractedVer);
                    SetState(UpdateState.Ready, extractedVer, null);
                }
                finally
                {
                    DeleteQuietly(downloadPath);
                    DeleteQuietly(extractPath);
                }
            }
            catch (Exception e)
            {
                Paths.Log("update: " + e.Message);
                if (manual && state != UpdateState.Ready) SetState(UpdateState.Error, null, "Couldn't reach GitHub");
            }
            finally
            {
                Interlocked.Exchange(ref busy, 0);
            }
        }

        static string FetchLatestTag()
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(LatestReleaseUrl);
            req.Method = "HEAD";
            req.AllowAutoRedirect = false;
            req.UserAgent = Net.UserAgent;
            req.Timeout = 15000;
            try
            {
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
                    return Updates.TagFromLocation(resp.Headers["Location"]);
                }
            }
            catch (WebException e)
            {
                HttpWebResponse resp = e.Response as HttpWebResponse;
                if (resp != null)
                {
                    using (resp)
                    {
                        return Updates.TagFromLocation(resp.Headers["Location"]);
                    }
                }
                throw;
            }
        }

        static void ExtractExe(string zipPath, string destPath)
        {
            DeleteQuietly(destPath);
            using (ZipArchive zip = ZipFile.OpenRead(zipPath))
            {
                foreach (ZipArchiveEntry entry in zip.Entries)
                {
                    if (string.Equals(entry.Name, "Broadsheet.exe", StringComparison.OrdinalIgnoreCase))
                    {
                        entry.ExtractToFile(destPath, true);
                        return;
                    }
                }
            }
            throw new InvalidDataException("Broadsheet.exe missing from zip");
        }

        static string ValidExeVersion(string path)
        {
            try
            {
                FileInfo fi = new FileInfo(path);
                if (!fi.Exists || fi.Length < MinExeBytes) return null;
                byte[] hdr = new byte[2];
                using (FileStream fs = File.OpenRead(path))
                {
                    if (fs.Read(hdr, 0, 2) != 2 || hdr[0] != 'M' || hdr[1] != 'Z') return null;
                }
                FileVersionInfo fvi = FileVersionInfo.GetVersionInfo(path);
                string ver = fvi.ProductVersion;
                if (Updates.ParseVersion(ver) == null) ver = fvi.FileVersion;
                return Updates.ParseVersion(ver) != null ? ver.Trim() : null;
            }
            catch (Exception)
            {
                return null;
            }
        }

        public static void ApplyPendingUpdate(bool relaunch)
        {
#if ENABLE_AUTO_UPDATE
            string exePath = ExePath();
            string newPath = exePath + ".new";
            string oldPath = exePath + ".old";
            if (!File.Exists(newPath)) return;
            string ver = ValidExeVersion(newPath);
            if (ver == null || !Updates.IsNewer(ver, CurrentVersion))
            {
                DeleteQuietly(newPath);
                return;
            }
            if (Interlocked.CompareExchange(ref swapApplied, 1, 0) != 0) return;
            try
            {
                DeleteQuietly(oldPath);
                File.Move(exePath, oldPath);
                try
                {
                    File.Move(newPath, exePath);
                }
                catch (Exception)
                {
                    if (!File.Exists(exePath) && File.Exists(oldPath)) File.Move(oldPath, exePath);
                    throw;
                }
                Paths.Log("update: swapped to v" + ver);
                if (relaunch)
                {
                    Process.Start(new ProcessStartInfo(exePath)
                    {
                        WorkingDirectory = Path.GetDirectoryName(exePath) ?? AppDomain.CurrentDomain.BaseDirectory
                    });
                    if (Application.Current != null) Application.Current.Shutdown();
                }
            }
            catch (Exception e)
            {
                Interlocked.Exchange(ref swapApplied, 0);
                Paths.Log("update swap failed: " + e.Message);
            }
#endif
        }

        static void SetState(UpdateState next, string remote, string err)
        {
            state = next;
            remoteVersion = remote;
            errorText = err;
            if (dispatcher != null)
            {
                dispatcher.BeginInvoke(new Action(() =>
                {
                    Action handler = StatusChanged;
                    if (handler != null) handler();
                }));
            }
        }

        static void DeleteQuietly(string path)
        {
            try
            {
                if (!string.IsNullOrEmpty(path) && File.Exists(path)) File.Delete(path);
            }
            catch (Exception) { }
        }
    }
}
