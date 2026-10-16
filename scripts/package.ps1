$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "Feric"
$AppVersion = "1.0.0"
$JarName = "teacher-classroom-$AppVersion.jar"
$DistDir = Join-Path $RootDir "dist"
$InputDir = Join-Path $RootDir "target\jpackage-input"
$IconPath = Join-Path $RootDir "logos\feric.ico"

$RuntimeImage = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($RuntimeImage)) {
    $JpackagePath = (Get-Command jpackage -ErrorAction Stop).Source
    $RuntimeImage = Split-Path (Split-Path $JpackagePath -Parent) -Parent
}
if (-not (Test-Path (Join-Path $RuntimeImage "bin\java.exe")) `
        -or -not (Test-Path (Join-Path $RuntimeImage "bin\javac.exe"))) {
    throw "The packaging runtime must be a full JDK installation: $RuntimeImage"
}

Push-Location $RootDir
try {
    mvn -B clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }

    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue `
        $InputDir, `
        (Join-Path $DistDir $AppName)
    Remove-Item -Force -ErrorAction SilentlyContinue `
        (Join-Path $DistDir "$AppName-Windows.zip")
    New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
    Copy-Item (Join-Path $RootDir "target\$JarName") `
        (Join-Path $InputDir $JarName)
    jpackage `
        --type app-image `
        --name $AppName `
        --app-version $AppVersion `
        --input $InputDir `
        --main-jar $JarName `
        --main-class "com.classroom.live.AppLauncher" `
        --runtime-image $RuntimeImage `
        --icon $IconPath `
        --dest $DistDir
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE"
    }

    $ZipPath = Join-Path $DistDir "$AppName-Windows.zip"
    Compress-Archive `
        -Path (Join-Path $DistDir $AppName) `
        -DestinationPath $ZipPath `
        -Force
    Write-Host "App image created in $DistDir"
}
finally {
    Pop-Location
}
