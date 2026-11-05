param(
    [string]$HostJar = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll',
    [string]$ToolsRoot = "$PSScriptRoot/../开发资料/tools",
    [string]$JdkHome = "$ToolsRoot/jdk25/jdk-25.0.4.1"
)
$ErrorActionPreference = 'Stop'
$HostJar = (Resolve-Path -LiteralPath $HostJar).Path
$ToolsRoot = (Resolve-Path -LiteralPath $ToolsRoot).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
Push-Location -LiteralPath $PSScriptRoot
try {
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'Provide -JdkHome pointing to JDK 25 or newer.' }
if (!(Test-Path -LiteralPath $HostJar)) { throw 'Provide -HostJar pointing to the installed SPW application archive.' }
$staging = Join-Path $PSScriptRoot ('build/staging-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $staging 'classes'
$tests = Join-Path $staging 'tests'
$output = Join-Path $PSScriptRoot 'build/libs/immersive-playback-0.1.31.spmod'
New-Item -ItemType Directory -Path $classes,$tests,(Split-Path $output) -Force | Out-Null
Write-Output "Build stage: $staging"
$hostReference = Join-Path $staging 'host.jar'
Copy-Item -LiteralPath $HostJar -Destination $hostReference
# The bundled host has optimized Kotlin inline bodies; compile against the original stdlib.
& "$PSScriptRoot/shared/build-host-resolver.ps1" -ToolsRoot $ToolsRoot -Classes $classes -JdkHome $JdkHome
$compileClasspath = "$classes;$ToolsRoot/kotlin-stdlib-2.4.20.jar;$ToolsRoot/spw-host.jar;$hostReference"
$compiler = Join-Path $ToolsRoot 'kotlin-compiler-embeddable.jar'
$composeCompiler = Join-Path $ToolsRoot 'kotlin-compose-compiler-plugin-embeddable.jar'
if (!(Test-Path -LiteralPath $compiler) -or !(Test-Path -LiteralPath $composeCompiler)) {
    throw 'Kotlin 2.4.20 compiler and Compose compiler are required in tools/.'
}
& $java -cp "$compiler;$compileClasspath;$ToolsRoot/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $compileClasspath "-Xplugin=$composeCompiler" -d $classes "$PSScriptRoot/src/main/kotlin/local/spw/immersive/NativeControlHook.kt"
if ($LASTEXITCODE -ne 0) { throw 'Compose hook compilation failed.' }
& $javac -encoding UTF-8 -proc:none --release 25 -cp "$classes;$hostReference" -d $classes "$PSScriptRoot/src/main/java/local/spw/immersive/ImmersivePlugin.java"
if ($LASTEXITCODE -ne 0) { throw 'Plugin compilation failed.' }
& "$PSScriptRoot/shared/test-host-resolver.ps1" -ToolsRoot $ToolsRoot -Classes $classes -Tests $tests -JdkHome $JdkHome -HostJar $hostReference
& $javac -encoding UTF-8 -proc:none --release 25 -cp "$tests;$classes;$hostReference" -d $tests "$PSScriptRoot/src/test/java/local/spw/immersive/ImmersiveSelfTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Self-check compilation failed.' }
& $java -ea --enable-native-access=ALL-UNNAMED -cp "$tests;$classes;$hostReference" local.spw.immersive.ImmersiveSelfTest
if ($LASTEXITCODE -ne 0) { throw 'Self-check failed; package not updated.' }
& $java -cp "$compiler;$compileClasspath;$ToolsRoot/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath "$tests;$classes;$compileClasspath" "-Xplugin=$composeCompiler" -d $tests "$PSScriptRoot/src/test/kotlin/local/spw/immersive/PointerShieldSelfTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Pointer integration test compilation failed.' }
& $java -ea --enable-native-access=ALL-UNNAMED "-Dskiko.library.path=$(Split-Path $HostJar)" -cp "$tests;$classes;$hostReference" local.spw.immersive.PointerShieldSelfTest
if ($LASTEXITCODE -ne 0) { throw 'Pointer integration test failed; package not updated.' }

$manifest = @(
    'Manifest-Version: 1.0'
    'Plugin-Class: local.spw.immersive.ImmersivePlugin'
    'Plugin-Id: local.spw.immersive'
    'Plugin-Version: 0.1.31'
    'Plugin-Provider: Local'
    'Plugin-Name: 沉浸播放'
    'Plugin-Description: Configurable immersive UI; Esc returns from playback'
    'Plugin-Has-Config: true'
    'Plugin-Permissions: key-bindings,class-transform'
    ''
    ''
) -join "`r`n"
$preferences = Get-Content -LiteralPath "$PSScriptRoot/src/main/resources/preference_config.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$options = @($preferences.configs.preferences)
if ($options.Count -ne 3 -or ($options | Where-Object key -eq 'immersive_playback').default_value -ne $true -or
    ($options | Where-Object key -eq 'immersive_other_pages').default_value -ne $true -or
    ($options | Where-Object key -eq 'lock_mouse').default_value -ne $false) {
    throw 'Expected the three accepted 0.1.27 configuration options and defaults.'
}
Copy-Item -LiteralPath "$PSScriptRoot/src/main/resources/preference_config.json" -Destination $classes
$archive = [IO.Compression.ZipArchive]::new([IO.File]::Open($output, [IO.FileMode]::Create), [IO.Compression.ZipArchiveMode]::Create)
try {
    $entry = $archive.CreateEntry('classes/META-INF/MANIFEST.MF')
    $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
    try { $writer.Write($manifest) } finally { $writer.Dispose() }
    foreach ($file in Get-ChildItem -LiteralPath $classes -File -Recurse) {
        $relative = [IO.Path]::GetRelativePath($classes, $file.FullName).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, "classes/$relative") | Out-Null
    }
} finally { $archive.Dispose() }
$package = [IO.Compression.ZipFile]::OpenRead($output)
try {
    if (!$package.GetEntry('classes/preference_config.json')) { throw 'Missing preferences configuration.' }
    if (!$package.GetEntry('classes/local/spw/immersive/ImmersivePlugin.class')) { throw 'Missing plugin class.' }
    if ($package.Entries.FullName -match 'SelfTest|NativeKeepAwake|NativeAwakeObserver|NativePlaybackActivity|PlaybackLayout|^lib/') { throw 'Unexpected tests, discarded features or runtime dependencies.' }
} finally { $package.Dispose() }
Write-Output "Package: $output ($((Get-Item -LiteralPath $output).Length) bytes)"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash)"
Remove-Item -LiteralPath $hostReference




} finally { Pop-Location }
