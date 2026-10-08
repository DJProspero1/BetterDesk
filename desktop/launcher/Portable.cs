using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

// Windows ships .NET Framework 4.x. The application JRE is embedded; no installer is used.
internal static class Portable
{
    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        var window = new Form {
            Text = "BetterDesk", Width = 340, Height = 135,
            StartPosition = FormStartPosition.CenterScreen, FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false
        };
        var label = new Label { Text = "Opening BetterDesk…", AutoSize = true, Left = 28, Top = 24 };
        window.Controls.Add(label);
        window.Shown += async delegate {
            try {
                var executable = await Task.Run(() => Prepare());
                if (args.Length == 0 || args[0] != "--prepare-only") {
                    Process.Start(new ProcessStartInfo(executable) { WorkingDirectory = Path.GetDirectoryName(executable), UseShellExecute = false });
                }
                window.Close();
            } catch (Exception) {
                MessageBox.Show("BetterDesk could not open. Check that your drive has free space, then try again.", "BetterDesk", MessageBoxButtons.OK, MessageBoxIcon.Error);
                Environment.ExitCode = 1;
                window.Close();
            }
        };
        Application.Run(window);
    }

    private static string Prepare()
    {
        var assembly = Assembly.GetExecutingAssembly();
        using (var payload = assembly.GetManifestResourceStream("BetterDesk.Payload")) {
            if (payload == null) throw new InvalidDataException();
            string digest;
            using (var sha = SHA256.Create()) digest = BitConverter.ToString(sha.ComputeHash(payload)).Replace("-", "").ToLowerInvariant();
            payload.Position = 0;
            var cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BetterDesk", "portable", digest);
            Directory.CreateDirectory(cache);
            using (var gate = OpenGate(Path.Combine(cache, ".lock"))) {
                var executable = Path.Combine(cache, "BetterDesk.exe");
                var complete = Path.Combine(cache, ".complete");
                if (!File.Exists(complete) || !File.Exists(executable)) {
                    using (var archive = new ZipArchive(payload, ZipArchiveMode.Read, true)) {
                        var prefix = Path.GetFullPath(cache) + Path.DirectorySeparatorChar;
                        foreach (var entry in archive.Entries) {
                            var relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                            var destination = Path.GetFullPath(Path.Combine(cache, relative));
                            if (!destination.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) || relative.Contains(":")) throw new InvalidDataException();
                            if (String.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(destination); continue; }
                            Directory.CreateDirectory(Path.GetDirectoryName(destination));
                            using (var source = entry.Open())
                            using (var target = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None)) source.CopyTo(target);
                        }
                    }
                    if (!File.Exists(executable)) throw new InvalidDataException();
                    File.WriteAllText(complete, digest);
                }
                return executable;
            }
        }
    }

    private static FileStream OpenGate(string path)
    {
        for (var attempt = 0; ; attempt++) {
            try { return new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
            catch (IOException) { if (attempt >= 300) throw; Thread.Sleep(100); }
        }
    }
}
