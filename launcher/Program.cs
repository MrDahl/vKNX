using System;
using System.Threading;
using System.Windows.Forms;

namespace VKnx.Launcher
{
    /// <summary>
    /// vKNX desktop launcher: one Windows window (WebView2) that starts the vKNX server (Java) in the background,
    /// shows the control panel and stops the server again when the window is closed.
    /// </summary>
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            bool created;
            using (var mutex = new Mutex(true, "Local\\vKNX-launcher", out created))
            {
                if (!created)
                {
                    MessageBox.Show("vKNX kører allerede.", "vKNX", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
        }
    }
}
