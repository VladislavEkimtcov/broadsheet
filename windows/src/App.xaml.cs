using System;
using System.IO;
using System.Windows;
using System.Windows.Threading;

namespace Broadsheet
{
    public partial class App : Application
    {
        void OnStartup(object sender, StartupEventArgs e)
        {
            DispatcherUnhandledException += OnCrash;
            MainWindow w = new MainWindow();
            w.Show();
        }

        /// <summary>Writes what went wrong to crash.log next to the caches and keeps going if it can.</summary>
        static void OnCrash(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            try
            {
                File.AppendAllText(Path.Combine(Paths.Local, "crash.log"), DateTime.Now.ToString("s") + "  " + e.Exception + "\r\n\r\n");
            }
            catch (Exception) { }
            e.Handled = true;
        }
    }
}
