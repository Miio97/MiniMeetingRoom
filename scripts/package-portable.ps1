param(
    [string]$JavaHome = 'C:/Program Files/Java/jdk-22',
    [ValidateSet('Client','Server')][string[]]$Roles = @('Client'),
    [switch]$Offline
)
. (Join-Path $PSScriptRoot 'common.ps1')
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$taskOutput = Join-Path $projectRoot "target/portable-$taskStamp"
$taskDependencies = Join-Path $taskOutput 'dependencies'
$taskRuntime = Join-Path $taskOutput 'runtime'
$taskMaven = Resolve-Maven
New-Item -ItemType Directory -Path $taskOutput | Out-Null
Push-Location -LiteralPath $projectRoot
try {
    $taskBuildArguments = @('-B', '-DskipTests', 'package', 'dependency:copy-dependencies', '-DincludeScope=runtime', "-DoutputDirectory=$taskDependencies")
    if ($Offline) { $taskBuildArguments = @('-o') + $taskBuildArguments }
    & $taskMaven @taskBuildArguments
    if ($LASTEXITCODE -ne 0) { throw 'Java packaging failed.' }
    & (Join-Path $PSScriptRoot 'build-client-runtime.ps1') -JavaHome $JavaHome -OutputPath $taskRuntime
    if ($Roles -contains 'Server') { & (Join-Path $PSScriptRoot 'build-video-engine.ps1') }
    foreach ($taskRole in $Roles) {
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
"runtime\bin\java.exe" --module-path "javafx" --add-modules javafx.controls -cp "app\MiniMeetingRoom.jar;lib\*" $taskMainClass
set "MMR_EXIT=%ERRORLEVEL%"
echo.
echo Application exited with code %MMR_EXIT%.
pause
exit /b %MMR_EXIT%
"@
        [IO.File]::WriteAllText((Join-Path $taskPackage "Start-$taskRole.cmd"), $taskLauncher.Replace("`r", "").Replace("`n", "`r`n"))
        if ($taskRole -eq 'Client') {
            Copy-Item -LiteralPath (Join-Path $projectRoot 'config/auth-client.properties') -Destination (Join-Path $taskPackage 'config/auth-client.properties')
            $taskClientText = [IO.File]::ReadAllText((Join-Path $taskPackage 'config/auth-client.properties'))
            $taskCertificateSetting = [regex]::Match($taskClientText, '(?m)^\s*auth\.certificate\s*=\s*([^\r\n]+)')
            if (-not $taskCertificateSetting.Success) { throw 'Client configuration must name its public certificate.' }
            $taskCertificateRelative = $taskCertificateSetting.Groups[1].Value.Trim().Replace('\','/')
            if ($taskCertificateRelative -notmatch '^config/[A-Za-z0-9_.-]+\.cer$') { throw 'Client certificate must be a public .cer directly inside config.' }
            $taskCertificateSource = Join-Path $projectRoot $taskCertificateRelative
            if (-not (Test-Path -LiteralPath $taskCertificateSource)) { throw 'Configured client certificate does not exist.' }
            Copy-Item -LiteralPath $taskCertificateSource -Destination (Join-Path $taskPackage $taskCertificateRelative)
            $taskHostMatch = [regex]::Match($taskClientText, '(?m)^\s*auth\.host\s*=\s*([^\r\n]+)')
            $taskServerHost = $taskHostMatch.Groups[1].Value.Trim()
            $taskReadme = @"
MINIMEETINGROOM - CLIENT WINDOWS x64
Giai nen TOAN BO ZIP vao thu muc co quyen ghi, sau do nhay dup Start-Client.cmd.
Khong can cai Java, Maven, Go hoac NetBeans. Khong chay truc tiep ben trong ZIP.
Server hien tai: $taskServerHost (auth 8443, signaling 8444, truyen tep 9001).
May server phai chay; cac may client can tham gia cung mang Radmin VPN voi server.
Chi may server chay server.Main. May nay chi chay Start-Client.cmd.
Neu doi server: sua auth.host, signaling.host, file.transfer.host trong config/auth-client.properties.
Chung chi $taskCertificateRelative phai dung voi server va IP; khong tat kiem tra TLS.
Thu dang nhap, vao cung phong, chat, gui tep, bat/tat camera va mic, chia se man hinh.
Quyen camera/mic theo tung tai khoan Windows; Windows van quyet dinh quyen truy cap thiet bi.
Khong co tai khoan dang nhap san hoac cau hinh database trong goi nay.
Khong gui du lieu phien dang nhap cho nguoi khac.
Giu nguyen cac thu muc app, lib, javafx, runtime, config khi chep sang may khac.
"@
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
        if (Get-ChildItem -LiteralPath (Join-Path $taskPackage 'config') -File | Where-Object { $_.Name -match '(?i)private|\.p12$|\.jks$|^auth-server\.properties$' }) { throw 'Private server configuration in distribution.' }
        Compress-Archive -LiteralPath $taskPackage -DestinationPath "$taskPackage.zip" -CompressionLevel Optimal
        Get-FileHash -LiteralPath "$taskPackage.zip" -Algorithm SHA256 | Format-List
    }
    Write-Output "PORTABLE_OUTPUT=$taskOutput"
} finally { Pop-Location }
