$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Maven {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenCommand) { return $mavenCommand.Source }
    $netBeansFolders = Get-ChildItem -LiteralPath $env:ProgramFiles -Directory -Filter 'NetBeans*' -ErrorAction SilentlyContinue
    foreach ($netBeansFolder in $netBeansFolders) {
        $bundledMaven = Join-Path $netBeansFolder.FullName 'netbeans\java\maven\bin\mvn.cmd'
        if (Test-Path -LiteralPath $bundledMaven) { return $bundledMaven }
    }
    throw 'Maven was not found. Install Maven or use the Maven bundled with NetBeans.'
}

function Resolve-Keytool {
    if ($env:JAVA_HOME) {
        $javaKeytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path -LiteralPath $javaKeytool) { return $javaKeytool }
    }
    $keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($keytoolCommand) { return $keytoolCommand.Source }
    $javaCommand = Get-Command java.exe -ErrorAction Stop
    $savedErrorPreference = $ErrorActionPreference
    try {
        # Java prints its settings to stderr even on success (Windows PowerShell 5.1).
        $ErrorActionPreference = 'Continue'
        $javaSettings = & $javaCommand.Source -XshowSettings:properties -version 2>&1 | Out-String
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($javaSettings -match 'java.home\s*=\s*([^\r\n]+)') {
        $javaKeytool = Join-Path $Matches[1].Trim() 'bin\keytool.exe'
        if (Test-Path -LiteralPath $javaKeytool) { return $javaKeytool }
    }
    throw 'keytool was not found. Install JDK 21 or newer and set JAVA_HOME.'
}
