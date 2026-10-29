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
        private readonly WebView2 web = new WebView2();
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
            status.Text = "Starter vKNX…";
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
                Fail("Programfilerne mangler:\n" + serverExe + "\n\nInstallér vKNX igen.");
                return;
            }
            port = ChoosePort();
            if (!StartServer()) return;
            if (!await WaitReady(60))
            {
                Fail("vKNX-motoren svarede ikke.\n\nLog: " + Path.Combine(dataDir, "console.log"));
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
                Fail("Kunne ikke starte vKNX-motoren:\n" + ex.Message);
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

        private async Task ShowWeb()
        {
            try
            {
                var userData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "vKNX", "webview");
                var env = await CoreWebView2Environment.CreateAsync(null, userData);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (Exception)
            {
                // WebView2 runtime missing: fall back to the default browser, the server keeps running
                try { Process.Start(Url); } catch { }
                status.Text = "WebView2 mangler på denne pc, så vKNX er åbnet i din browser.\n\nLuk dette vindue for at stoppe vKNX.\n\n" + Url;
                return;
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
                    status.Text = "Gemmer og lukker vKNX…";
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
