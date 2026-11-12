param(
    [string]$ApkPath = "D:\workspace\koubo-studio\app\build\outputs\apk\debug\app-debug.apk"
)
$ErrorActionPreference = "Continue"
$sdk  = "D:\tools\android-sdk"
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:JAVA_HOME        = "D:\tools\build-env\jdk-17.0.21+9"
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$avm = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"

Write-Output "== 1. create AVD (skip if exists) =="
$avds = (& $avm list avd 2>$null) | Out-String
if ($avds -notmatch "koubo") {
    "no" | & $avm create avd -n koubo -k "system-images;android-34;google_apis;x86_64" -d "pixel_6" --force 2>&1 | Select-Object -Last 2
} else { Write-Output "AVD koubo exists" }

Write-Output "== 2. launch emulator headless =="
$emuProc = Start-Process -FilePath $emu -ArgumentList "-avd koubo -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot" -PassThru -WindowStyle Hidden
Write-Output "emulator pid=$($emuProc.Id)"

Write-Output "== 3. wait for boot (max 300s) =="
& $adb wait-for-device
$booted = $false
for ($i = 0; $i -lt 60; $i++) {
    $b = & $adb shell getprop sys.boot_completed 2>$null
    if ("$b".Trim() -eq "1") { $booted = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $booted) { Write-Output "BOOT TIMEOUT"; try { Stop-Process -Id $emuProc.Id -Force } catch {}; exit 1 }
Write-Output "boot completed after ~$([int](($i+1)*5)) s"
Start-Sleep -Seconds 8

Write-Output "== 4. install APK =="
& $adb install -r $ApkPath 2>&1 | Select-Object -Last 2

Write-Output "== 5. clear log + launch app =="
& $adb logcat -c
& $adb shell am start -n "com.mxd.koubo/.MainActivity"
Start-Sleep -Seconds 6

Write-Output "== 6. crash check (AndroidRuntime:E) =="
$crash = (& $adb logcat -d -s AndroidRuntime:E 2>$null) | Out-String
if ($crash.Trim().Length -gt 30) {
    Write-Output "!!! CRASH DETECTED !!!"
    $crash | Out-File "D:\workspace\koubo-studio\emulator-crash.txt" -Encoding UTF8
    $crash.Split("`n") | Select-Object -First 40 | ForEach-Object { Write-Output $_ }
} else {
    Write-Output "NO CRASH - app is running in foreground"
}

Write-Output "== 7. screenshots =="
& $adb exec-out screencap -p > "D:\workspace\koubo-studio\emu-home.png"
& $adb shell input tap 200 500
Start-Sleep -Seconds 3
& $adb exec-out screencap -p > "D:\workspace\koubo-studio\emu-detail.png"
$crash2 = (& $adb logcat -d -s AndroidRuntime:E 2>$null) | Out-String
if ($crash2.Trim().Length -gt 30) { Write-Output "CRASH after detail tap:"; $crash2.Split("`n") | Select-Object -First 25 }

Write-Output "== 8. done (emulator kept running) =="
Write-Output "stop with: & '$adb' emu kill"
