@echo off
if exist "%USERPROFILE%\.claude\plugins\cache\bitkentech\shipsmooth\0.3.10\runtime" (
    xcopy /E /Y /I "%USERPROFILE%\.claude\plugins\cache\bitkentech\shipsmooth\0.3.10\runtime" "%LOCALAPPDATA%\shipsmooth\0.3.10\runtime"
)
