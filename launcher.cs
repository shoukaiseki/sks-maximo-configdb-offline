using System;
using System.Diagnostics;
using System.IO;
using System.Text;

class Launcher {
    static void Main() {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string configPath = Path.Combine(dir, "config.properties");
        string javaCmd = "java.exe";
        string jdkHome = "";

        if (File.Exists(configPath)) {
            foreach (string line in File.ReadAllLines(configPath)) {
                if (line.StartsWith("jdk.home=")) {
                    jdkHome = line.Substring(9).Trim();
                    string javaExe = Path.Combine(jdkHome, "bin", "java.exe");
                    if (File.Exists(javaExe)) javaCmd = javaExe;
                    break;
                }
            }
        }

        string jarDir = Path.Combine(dir, "bin");
        string classpath = "\"bin;maximo\\businessobjects.jar;lib\\snakeyaml-2.5.jar;maximo\\lib\\db2jcc.jar;maximo\\lib\\db2jcc_license_cu.jar;maximo\\lib\\icu4j.jar;maximo\\lib\\j2ee.jar;maximo\\lib\\javax.mail-api-1.6.2.jar;maximo\\lib\\log4j-core-2.25.3.jar\"";

        ProcessStartInfo psi = new ProcessStartInfo();
        psi.FileName = javaCmd;
        psi.Arguments = "-cp " + classpath + " ConfigDBUI";
        psi.WorkingDirectory = dir;
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.CreateNoWindow = true;

        try {
            using (Process p = Process.Start(psi)) {
                p.WaitForExit();
                if (p.ExitCode != 0) {
                    Console.Error.WriteLine("Exit code: " + p.ExitCode);
                    Console.ReadKey(true);
                }
            }
        } catch (Exception ex) {
            Console.Error.WriteLine("Error: " + ex.Message);
            Console.ReadKey(true);
        }
    }
}
