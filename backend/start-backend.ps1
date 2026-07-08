param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

function Get-JavaMajorVersion {
    param([string]$JavaExe)

    $versionOutput = & $JavaExe -version 2>&1
    $versionLine = $versionOutput | Select-String 'version "([^"]+)"' | Select-Object -First 1
    if (-not $versionLine) {
        return $null
    }

    $version = $versionLine.Matches[0].Groups[1].Value
    if ($version.StartsWith("1.")) {
        return [int]($version.Split(".")[1])
    }

    return [int]($version.Split(".")[0])
}

function Test-JdkHome {
    param([string]$Home)

    if (-not $Home) {
        return $false
    }

    $javaExe = Join-Path $Home "bin\java.exe"
    if (-not (Test-Path $javaExe)) {
        return $false
    }

    $major = Get-JavaMajorVersion $javaExe
    return $major -ge 21
}

$candidateHomes = @(
    $JdkHome,
    (Join-Path $env:USERPROFILE ".jdks\openjdk-23.0.2"),
    (Join-Path $env:USERPROFILE ".jdks\openjdk-21"),
    "C:\Program Files\Java\jdk-23",
    "C:\Program Files\Java\jdk-21"
) | Where-Object { $_ } | Select-Object -Unique

$selectedJdk = $candidateHomes | Where-Object { Test-JdkHome $_ } | Select-Object -First 1
if (-not $selectedJdk) {
    throw "No JDK 21+ found. Install JDK 21 or pass -JdkHome <path-to-jdk>."
}

$env:JAVA_HOME = $selectedJdk
$env:Path = "$selectedJdk\bin;$env:Path"

Set-Location $PSScriptRoot
mvn spring-boot:run
