param([string]$JavaHome = 'C:/Program Files/Java/jdk-22')
. (Join-Path $PSScriptRoot 'common.ps1')
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$taskOutput = Join-Path $projectRoot "target/portable-$taskStamp"
$taskDependencies = Join-Path $taskOutput 'dependencies'
$taskRuntime = Join-Path $taskOutput 'runtime'
$taskMaven = Resolve-Maven
New-Item -ItemType Directory -Path $taskOutput | Out-Null
Push-Location -LiteralPath $projectRoot
try {
    & $taskMaven -B '-DskipTests' package dependency:copy-dependencies '-DincludeScope=runtime' "-DoutputDirectory=$taskDependencies"
    if ($LASTEXITCODE -ne 0) { throw 'Java packaging failed.' }
    & (Join-Path $PSScriptRoot 'build-client-runtime.ps1') -JavaHome $JavaHome -OutputPath $taskRuntime
    & (Join-Path $PSScriptRoot 'build-video-engine.ps1')
    foreach ($taskRole in @('Client', 'Server')) {
        $taskPackage = Join-Path $taskOutput "MiniMeetingRoom-$taskRole-Windows-x64"
        foreach ($taskFolder in @('app', 'lib', 'javafx', 'config')) {
            New-Item -ItemType Directory -Path (Join-Path $taskPackage $taskFolder) -Force | Out-Null
        }
        Copy-Item -LiteralPath $taskRuntime -Destination (Join-Path $taskPackage 'runtime') -Recurse
        Copy-Item -LiteralPath (Join-Path $projectRoot 'target/MiniMeetingRoom-1.0-SNAPSHOT.jar') -Destination (Join-Path $taskPackage 'app/MiniMeetingRoom.jar')
        foreach ($taskJar in (Get-ChildItem -LiteralPath $taskDependencies -Filter '*.jar')) {
            $taskLibraryFolder = if ($taskJar.Name -like 'javafx-*') { 'javafx' } else { 'lib' }
            Copy-Item -LiteralPath $taskJar.FullName -Destination (Join-Path $taskPackage $taskLibraryFolder)
        }
        $taskMainClass = if ($taskRole -eq 'Client') { 'client.Main' } else { 'server.Main' }
        $taskLauncher = @"
@echo off
setlocal
cd /d "%~dp0"
if not defined MMR_DIAGNOSTICS_RUN_ID set "MMR_DIAGNOSTICS_RUN_ID=portable-test"
"runtime\bin\java.exe" --module-path "javafx" --add-modules javafx.controls -cp "app\MiniMeetingRoom.jar;lib\*" $taskMainClass
set "MMR_EXIT=%ERRORLEVEL%"
echo.
echo Application exited with code %MMR_EXIT%. Diagnostics: logs\diagnostics
pause
exit /b %MMR_EXIT%
"@
        [IO.File]::WriteAllText((Join-Path $taskPackage "Start-$taskRole.cmd"), $taskLauncher.Replace("`n", "`r`n"))
        if ($taskRole -eq 'Client') {
            Copy-Item -LiteralPath (Join-Path $projectRoot 'config/auth-client.properties') -Destination (Join-Path $taskPackage 'config/auth-client.properties')
            Copy-Item -LiteralPath (Join-Path $projectRoot 'config/auth-server.cer') -Destination (Join-Path $taskPackage 'config/auth-server.cer')
            $taskReadme = @'
MINIMEETINGROOM - CLIENT WINDOWS x64
Giai nen toan bo ZIP truoc khi chay Start-Client.cmd. Khong can cai Java/Maven.
Server hien tai: 10.231.125.102. Cac may can ket noi duoc mang LAN/ZeroTier cua server.
Neu doi server: sua auth.host, signaling.host, file.transfer.host trong config/auth-client.properties.
Chung chi config/auth-server.cer phai dung voi server va dia chi truy cap; khong tat kiem tra TLS.
Chi may server can chay Start-Server.cmd. May client chi chay Start-Client.cmd.
Thu: 2 tai khoan vao cung phong, gui chat hai chieu, thu reconnect va dong/mo lai phong.
Chat van ban luu RAM, toi da 500 tin/phong; dong phong hoac restart server se mat lich su.
Diagnostics duoc luu trong thu muc logs. Co the gui file log cua tung may de phan tich.
Ban nay gom thay doi chat RAM da kiem thu, chua ap dung cac de xuat toi uu bo sung.
'@
        } else {
            New-Item -ItemType Directory -Path (Join-Path $taskPackage '.tools/bin'), (Join-Path $taskPackage 'media/video') -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $projectRoot '.tools/bin/mmr-video.exe') -Destination (Join-Path $taskPackage '.tools/bin/mmr-video.exe')
            Set-Content -LiteralPath (Join-Path $taskPackage 'media/video/PREBUILT.txt') -Value 'Prebuilt SFU is in .tools/bin/mmr-video.exe. No Go toolchain needed.'
            Copy-Item -LiteralPath (Join-Path $projectRoot 'config/auth-server.properties.example') -Destination (Join-Path $taskPackage 'config/auth-server.properties.example')
            $taskReadme = @'
MINIMEETINGROOM - SERVER WINDOWS x64
Giai nen ZIP. Truoc khi chay Start-Server.cmd, sao chep tu MAY SERVER CU:
  config/auth-server.properties
  config/meeting-server.private.properties (giu nguyen instance ownership)
  config/video-server.private.properties
  keystore .p12 dung duong dan auth.keystore trong auth-server.properties.
Goi nay KHONG chua mat khau database, khoa rieng hoac du lieu storage.
Neu chuyen may server, can chuyen storage khi can giu cac tep dang co.
Dung server cu truoc khi chay ban moi voi cung cau hinh/instance. Khong chay hai ban cung luc.
Giu cau hinh Railway hien co. Dong goi khong ket noi hay chay migration tren Railway.
De nhan ket noi tu may khac: auth.bind, signaling.bind, file.transfer.bind phai lang nghe tren IP mang phu hop hoac 0.0.0.0.
Mo firewall TCP theo auth.port, signaling.port, file.transfer.port (thuong 8443, 8444, 9001).
Video/audio WebRTC con can duong UDP/ICE thong giua cac may va SFU; chat chay duoc chua chung minh media chay duoc.
Khi doi IP server, cap nhat host trong config client va chung chi TLS phu hop.
Khong gui cac file cau hinh rieng/keystore cua server cho may client.
Khong can cai Java, Maven, Go. Start-Server.cmd se tu khoi dong SFU da bien dich.
'@
        }
        [IO.File]::WriteAllText((Join-Path $taskPackage 'HUONG-DAN.txt'), $taskReadme, [Text.UTF8Encoding]::new($false))
        Compress-Archive -LiteralPath $taskPackage -DestinationPath "$taskPackage.zip" -CompressionLevel Optimal
        Get-FileHash -LiteralPath "$taskPackage.zip" -Algorithm SHA256 | Format-List
    }
    Write-Output "PORTABLE_OUTPUT=$taskOutput"
} finally { Pop-Location }
