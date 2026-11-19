param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$ReleaseKeystore,
    [string]$SigningLineage,
    [string]$SigningAlias = 'bandorilovepet'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if (!$Sdk -or !$Jdk) { throw 'Set ANDROID_HOME and JAVA_HOME, or pass -Sdk and -Jdk.' }
$release = [bool]$ReleaseKeystore
if ($release) {
    if (!$SigningLineage -or !$env:BANDORILOVEPET_ANDROID_KEYSTORE_PASS) {
        throw 'Release builds require the signing lineage and BANDORILOVEPET_ANDROID_KEYSTORE_PASS.'
    }
    $ReleaseKeystore = (Resolve-Path -LiteralPath $ReleaseKeystore).Path
    $SigningLineage = (Resolve-Path -LiteralPath $SigningLineage).Path
}
$api = Join-Path $Sdk 'platforms/android-35/android.jar'
$tools = Join-Path $Sdk 'build-tools/35.0.0'
$repo = Split-Path $PSScriptRoot
$work = Join-Path $PSScriptRoot $(if ($release) { 'build/release' } else { 'build/debug' })
$dist = Join-Path $PSScriptRoot 'dist'
$source = Join-Path $PSScriptRoot 'src'
$assets = Join-Path $work 'assets'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
New-Item -ItemType Directory -Force $work,$dist,$assets,$classes,$dex | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'web/*') -Destination $assets -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'vendor/*') -Destination $assets -Force
foreach ($name in @('pixi.min.js','index.min.js')) {
    Copy-Item -LiteralPath (Join-Path $repo "panel/web/vendor/$name") -Destination (Join-Path $assets $name) -Force
    $oldAsset = Join-Path $assets "vendor/$name"
    if (Test-Path -LiteralPath $oldAsset -PathType Leaf) { Remove-Item -LiteralPath $oldAsset }
}
Copy-Item -LiteralPath (Join-Path $repo 'panel/web/favicon.svg'),(Join-Path $repo 'panel/THIRD_PARTY_NOTICES.txt') -Destination $assets -Force
$compiled = Join-Path $work 'resources.zip'
$unsigned = Join-Path $work 'base.apk'
$aligned = Join-Path $work 'aligned.apk'
$output = Join-Path $dist $(if ($release) { 'BandoriLovePet.apk' } else { 'BandoriLovePet-debug.apk' })
$versionName = if ($release) { '1.0.1' } else { '1.0.1-dev' }
$debugLink = if (!$release) { @('--debug-mode') } else { @() }
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + [IO.Path]::PathSeparator + $env:PATH
& (Join-Path $tools 'aapt2.exe') compile --dir (Join-Path $PSScriptRoot 'res') -o $compiled
& (Join-Path $tools 'aapt2.exe') link -I $api --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') @debugLink --version-code 4 --version-name $versionName --java $work -A $assets -0 js -o $unsigned $compiled
$javaSources = @(Get-ChildItem -LiteralPath $source -Recurse -Filter '*.java' | ForEach-Object FullName)
$javaSources += Join-Path $work 'io/bandorilove/lovepet/R.java'
& (Join-Path $Jdk 'bin/javac.exe') --release 11 -encoding UTF-8 -cp $api -d $classes @javaSources
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $tools 'd8.bat') --min-api 30 --lib $api --output $dex @classFiles
Push-Location $dex
try { & (Join-Path $Jdk 'bin/jar.exe') uf $unsigned classes.dex } finally { Pop-Location }
& (Join-Path $tools 'zipalign.exe') -f 4 $unsigned $aligned
$signer = Join-Path $tools 'apksigner.bat'
if ($release) {
    & $signer sign --ks $ReleaseKeystore --ks-key-alias $SigningAlias --ks-pass env:BANDORILOVEPET_ANDROID_KEYSTORE_PASS --key-pass env:BANDORILOVEPET_ANDROID_KEYSTORE_PASS --lineage $SigningLineage --rotation-min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false --out $output $aligned
} else {
    $key = Join-Path $work 'debug.keystore'
    if (!(Test-Path -LiteralPath $key)) {
        & (Join-Path $Jdk 'bin/keytool.exe') -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=BandoriLove,C=US'
    }
    & $signer sign --ks $key --ks-pass pass:android --key-pass pass:android --out $output $aligned
}
& $signer verify --verbose --print-certs $output
if ($release -and ((& (Join-Path $tools 'aapt.exe') dump badging $output) -match '^application-debuggable$')) {
    throw 'The production APK enables application debugging.'
}
Get-Item -LiteralPath $output
