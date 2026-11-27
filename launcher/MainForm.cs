using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace VKnx.Launcher
{
    internal sealed class MainForm : Form
    {
        private WebView2 web = new WebView2();
        private readonly Label status = new Label();
        private Process server;
        private int port;
        private string dataDir;
        private string serverExe;
        private bool closing;

        public MainForm()
        {
            Text = "vKNX";
            Width = 1500;
            Height = 950;
            MinimumSize = new Size(900, 600);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(11, 18, 32);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            status.Dock = DockStyle.Fill;
            status.ForeColor = Color.FromArgb(232, 238, 249);
            status.Font = new Font("Segoe UI", 13f);
            status.TextAlign = ContentAlignment.MiddleCenter;
            status.Text = "Starting vKNX…";
            web.Dock = DockStyle.Fill;
            web.Visible = false;
            Controls.Add(web);
            Controls.Add(status);

            Shown += async (s, e) => await StartAsync();
            FormClosing += OnFormClosing;
        }

        private string Url { get { return "http://127.0.0.1:" + port + "/"; } }

        private async Task StartAsync()
        {
            var baseDir = AppDomain.CurrentDomain.BaseDirectory;
            serverExe = Path.Combine(baseDir, "server", "vKNX-Server.exe");
            dataDir = Environment.GetEnvironmentVariable("VKNX_DATA_DIR");
            if (string.IsNullOrEmpty(dataDir))
                dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "vKNX", "data");
            Directory.CreateDirectory(dataDir);

            if (!File.Exists(serverExe))
            {
                Fail("Program files are missing:\n" + serverExe + "\n\nPlease reinstall vKNX.");
                return;
            }
            port = ChoosePort();
            if (!StartServer()) return;
            if (!await WaitReady(60))
            {
                Fail("The vKNX engine did not respond.\n\nLog: " + Path.Combine(dataDir, "console.log"));
                return;
            }
            await ShowWeb();
        }

        private int ChoosePort()
        {
            var fixedPort = Environment.GetEnvironmentVariable("VKNX_PORT");
            int p;
            if (!string.IsNullOrEmpty(fixedPort) && int.TryParse(fixedPort, out p)) return p;
            // a stable port keeps the saved theme/language (browser storage is per origin)
            for (p = 18080; p < 18100; p++)
                if (IsFree(p)) return p;
            var l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            p = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            return p;
        }

        private static bool IsFree(int p)
        {
            try
            {
                var l = new TcpListener(IPAddress.Loopback, p);
                l.Start();
                l.Stop();
                return true;
            }
            catch { return false; }
        }

        private bool StartServer()
        {
            try
            {
                var psi = new ProcessStartInfo(serverExe,
                    "--no-browser --supervised --console-log --dir \"" + dataDir + "\" --web " + port)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WorkingDirectory = Path.GetDirectoryName(serverExe)
                };
                var p = Process.Start(psi);
                p.EnableRaisingEvents = true;
                p.Exited += (s, e) => OnServerExited(p);
                server = p;
                return true;
            }
            catch (Exception ex)
            {
                Fail("Could not start the vKNX engine:\n" + ex.Message);
                return false;
            }
        }

        private async Task<bool> WaitReady(int seconds)
        {
            var end = DateTime.UtcNow.AddSeconds(seconds);
            while (DateTime.UtcNow < end)
            {
                if (server == null || server.HasExited) return false;
                try
                {
                    var req = (HttpWebRequest)WebRequest.Create(Url + "api/state");
                    req.Timeout = 1500;
                    using (var resp = (HttpWebResponse)req.GetResponse())
                        if (resp.StatusCode == HttpStatusCode.OK) return true;
                }
                catch { }
                await Task.Delay(300);
            }
            return false;
        }

        private const string WebView2Setup = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"; // Microsoft's small Evergreen bootstrapper

        private async Task<bool> TryInitWebView()
        {
            try
            {
                var userData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "vKNX", "webview");
                var env = await CoreWebView2Environment.CreateAsync(null, userData);
                await web.EnsureCoreWebView2Async(env);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>A control whose initialisation failed cannot be initialised again: use a fresh one.</summary>
        private void NewWebView()
        {
            Controls.Remove(web);
            web.Dispose();
            web = new WebView2();
            web.Dock = DockStyle.Fill;
            web.Visible = false;
            Controls.Add(web);
        }

        /// <summary>Downloads Microsoft's WebView2 setup program and runs it. Returns null if it ran without error, otherwise a reason.</summary>
        private static string InstallWebView2(bool elevated)
        {
            try
            {
                ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
                var exe = Path.Combine(Path.GetTempPath(), "vknx-MicrosoftEdgeWebview2Setup.exe");
                using (var wc = new WebClient()) wc.DownloadFile(WebView2Setup, exe);
                // only run the file if it carries a Microsoft signature (the download itself is over https from Microsoft)
                var cert = new System.Security.Cryptography.X509Certificates.X509Certificate2(
                    System.Security.Cryptography.X509Certificates.X509Certificate.CreateFromSignedFile(exe));
                if (cert.Subject.IndexOf("Microsoft Corporation", StringComparison.OrdinalIgnoreCase) < 0)
                    return "the downloaded file is not signed by Microsoft";
                var psi = new ProcessStartInfo(exe, "/silent /install") { UseShellExecute = true };
                if (elevated) psi.Verb = "runas"; // asks Windows for administrator rights
                using (var p = Process.Start(psi))
                {
                    if (p == null) return null;
                    p.WaitForExit();
                    return p.ExitCode == 0 ? null : "the Microsoft setup program stopped with code 0x" + p.ExitCode.ToString("X");
                }
            }
            catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223)
            {
                return "administrator permission was not given";
            }
            catch (WebException ex)
            {
                return "could not download the setup program from Microsoft (" + ex.Message + ")";
            }
            catch (Exception ex)
            {
                return ex.Message;
            }
        }

        private async Task ShowWeb()
        {
            if (!await TryInitWebView())
            {
                // WebView2 runtime missing: offer to install it, otherwise fall back to the default browser (the server keeps running)
                var ready = false;
                string why = null;
                var yes = false;
                try
                {
                    yes = MessageBox.Show(this, "vKNX needs the free Microsoft WebView2 Runtime to show its own window.\n\nInstall it now? It is a small download from Microsoft and takes about a minute. Windows may ask for permission.",
                        "vKNX", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes;
                }
                catch { }
                if (yes)
                {
                    status.Text = "Installing the Microsoft WebView2 Runtime…\n\nThis takes about a minute.";
                    Refresh();
                    foreach (var elevated in new[] { false, true }) // first as the current user, then with administrator rights
                    {
                        if (elevated) status.Text = "Installing the Microsoft WebView2 Runtime…\n\nWindows may ask for permission.";
                        why = await Task.Run(() => InstallWebView2(elevated));
                        NewWebView();
                        if (await TryInitWebView()) { ready = true; break; }
                        if (why == null) why = "the setup finished, but vKNX still cannot use WebView2";
                    }
                    if (!ready) NewWebView();
                }
                if (!ready)
                {
                    try { Process.Start(Url); } catch { }
                    status.Text = "WebView2 is missing on this PC, so vKNX was opened in your browser.\n\nClose this window to stop vKNX.\n\n" + Url
                        + (why != null ? "\n\nThe automatic install did not work: " + why + "." : "")
                        + "\n\nFor the vKNX window, install the free Microsoft WebView2 Runtime (a small setup program, not the .cab files):\n" + WebView2Setup;
                    return;
                }
            }
            var cw = web.CoreWebView2;
            cw.Settings.AreDevToolsEnabled = false;
            cw.Settings.IsStatusBarEnabled = false;
            cw.NewWindowRequested += (s, e) => { e.Handled = true; OpenExternal(e.Uri); };
            cw.NavigationStarting += (s, e) =>
            {
                if (!e.Uri.StartsWith("http://127.0.0.1:" + port) && !e.Uri.StartsWith("http://localhost:" + port) && !e.Uri.StartsWith("about:"))
                {
                    e.Cancel = true;
                    OpenExternal(e.Uri);
                }
            };
            cw.Navigate(Url);
            web.Visible = true;
            status.Visible = false;
        }

        private static void OpenExternal(string uri)
        {
            if (uri.StartsWith("http://") || uri.StartsWith("https://"))
                try { Process.Start(uri); } catch { }
        }

        private void OnServerExited(Process p)
        {
            if (closing) return;
            int code;
            try { code = p.ExitCode; } catch { code = -1; }
            try { BeginInvoke(new Action(async () => await HandleExit(p, code))); } catch { }
        }

        private async Task HandleExit(Process p, int code)
        {
            if (closing || p != server) return;
            if (code == 77) // update installer was started: the installer replaces the files and starts vKNX again
            {
                closing = true;
                Close();
                return;
            }
            if (code == 75) // "restart" requested from the settings dialog
            {
                if (!StartServer()) return;
                if (await WaitReady(60) && web.CoreWebView2 != null) web.CoreWebView2.Navigate(Url);
                return;
            }
            web.Visible = false;
            status.Visible = true;
            status.Text = "vKNX-motoren stoppede uventet (kode " + code + ").\n\nLog: " + Path.Combine(dataDir, "console.log");
        }

        private void OnFormClosing(object sender, FormClosingEventArgs e)
        {
            if (closing) return;
            closing = true;
            if (server == null) return;
            try
            {
                if (!server.HasExited)
                {
                    status.Visible = true;
                    web.Visible = false;
                    status.Text = "Saving and closing vKNX…";
                    Refresh();
                    try
                    {
                        var req = (HttpWebRequest)WebRequest.Create(Url + "api/shutdown");
                        req.Method = "POST";
                        req.ContentLength = 0;
                        req.Timeout = 2000;
                        using (req.GetResponse()) { }
                    }
                    catch { }
                    if (!server.WaitForExit(8000))
                    {
                        var kill = Process.Start(new ProcessStartInfo("taskkill", "/PID " + server.Id + " /T /F") { CreateNoWindow = true, UseShellExecute = false });
                        if (kill != null) kill.WaitForExit(3000);
                    }
                }
            }
            catch { }
        }

        private void Fail(string message)
        {
            web.Visible = false;
            status.Visible = true;
            status.Text = message;
        }
    }
}
