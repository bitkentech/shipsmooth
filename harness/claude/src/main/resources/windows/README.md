# shipsmooth (Windows)

This page only shows how to install the Shipsmooth coding assistant plugin on Windows. To learn more about Shipsmooth, see the [Shipsmooth README](https://github.com/bitkentech/shipsmooth).

## How to Install

**Prerequisite:** [Git for Windows](https://git-scm.com/download/win) must be installed and Git Bash must be on your PATH. Claude Code's hook runner requires `bash` to launch the runtime installer. See [Claude Code Additional Dependencies](https://code.claude.com/docs/en/setup#additional-dependencies).

Run the following commands within Claude Code:

1. Register the marketplace (one-time setup):
   ```
   /plugin marketplace add bitkentech/shipsmooth-windows
   ```

2. Install the plugin:
   ```
   /plugin install shipsmooth@bitkentech
   ```

3. Reload plugins:
   ```
   /reload-plugins
   ```

4. Restart Claude Code — the session start hook needs to run once to copy the Java runtime to your local machine.

5. Launch the plugin:
   ```
   /shipsmooth:start
   ```

## Troubleshooting

**Check whether Git Bash is on PATH:**

Open CMD or PowerShell and run:

```
where.exe bash
```

If it prints a path (e.g. `C:\Program Files\Git\bin\bash.exe`), Git Bash is available and the hook will fire automatically on the next Claude Code session start.

**If Git Bash is missing — manual install fallback:**

You can run the runtime installer directly without Git Bash:

1. Open Explorer and paste this path into the address bar:
   ```
   %USERPROFILE%\.claude\plugins\cache\bitkentech\shipsmooth
   ```

2. Open the version-numbered folder (e.g. `0.3.10`), then open the `hooks` folder inside it.

3. Double-click `install-runtime.bat`, or run it from CMD:
   ```
   "%USERPROFILE%\.claude\plugins\cache\bitkentech\shipsmooth\<version>\hooks\install-runtime.bat"
   ```

**Verify the install succeeded:**

1. Open Explorer and paste this path into the address bar:
   ```
   %LOCALAPPDATA%\shipsmooth
   ```

2. Open the version-numbered folder (e.g. `0.3.10`) and confirm that `runtime\bin\shipsmooth.bat` exists inside it.

If it does, the runtime is installed and the plugin is ready to use.
