param([string]$ToolsRoot = "$PSScriptRoot/../../开发资料/tools", [string]$Version, [string]$Package, [string]$BuildLog, [string]$SourceRoot)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot
$stage = (Get-Content -LiteralPath $BuildLog -TotalCount 1).Substring('Build stage: '.Length)
$classes = Join-Path $stage 'classes'
if (!(Test-Path -LiteralPath $classes)) { throw 'Missing tested class directory.' }
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Package))
try {
    $names = @($zip.Entries.FullName)
    if ($names | Where-Object { $_ -match 'SelfTest|Fixture|HookInternals|^classes/fixtures/|^classes/(com/xuncorp|com/moriafly|androidx|org/jetbrains/kotlin)/' }) { throw 'Unexpected test or host/compiler classes in package.' }
    $duplicates = @($names | Group-Object | Where-Object Count -gt 1)
    if ($duplicates.Count) { throw 'Duplicate package entries.' }
    foreach ($file in Get-ChildItem -LiteralPath $classes -File -Recurse) {
        $relative = [IO.Path]::GetRelativePath($classes, $file.FullName).Replace('\', '/')
        $entry = $zip.GetEntry("classes/$relative")
        if (!$entry) { throw "Missing tested file: $relative" }
        $stream = $entry.Open()
        try { $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
        if ($hash -ne (Get-FileHash -LiteralPath $file.FullName).Hash) { throw "Untested package file: $relative" }
    }
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -eq 'classes/META-INF/MANIFEST.MF') { continue }
        if (!(Test-Path -LiteralPath (Join-Path $classes $entry.FullName.Substring('classes/'.Length)))) { throw "Extra package file: $($entry.FullName)" }
    }
    $reader = [IO.StreamReader]::new($zip.GetEntry('classes/META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if (!$manifest.Contains("Plugin-Version: $Version`r`n")) { throw 'Wrong release version.' }
    foreach ($required in 'classes/local/spw/host/HostResolver.class', 'classes/licenses/Kotlin-Metadata-LICENSE.txt', 'classes/licenses/Kotlin-Metadata-NOTICE.txt') {
        if (!$zip.GetEntry($required)) { throw "Missing dynamic resolver dependency: $required" }
    }
    $resolver = $zip.GetEntry('classes/local/spw/host/HostResolver.class').Open()
    try { $resolverHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($resolver)) } finally { $resolver.Dispose() }
    $sources = @(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File | Where-Object Extension -in '.java','.kt','.json' | ForEach-Object {
        @{path = [IO.Path]::GetRelativePath($workspace, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash}
    })
    $audit = [ordered]@{
        version = $Version; package = $Package; bytes = (Get-Item -LiteralPath $Package).Length
        sha256 = (Get-FileHash -LiteralPath $Package).Hash; testedStage = $stage; buildLog = $BuildLog
        entries = $names.Count; allEntriesMatchTestedStage = $true; noTestOrHostClasses = $true
        resolverSha256 = $resolverHash; manifest = $manifest; sourceHashes = $sources
        metadataDependencySha256 = (Get-FileHash -LiteralPath (Join-Path $ToolsRoot 'kotlin-metadata-jvm-2.4.20.jar')).Hash
        sharedSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $workspace 'shared/src/main/java/local/spw/host/HostResolver.java')).Hash
    }
    $destination = Join-Path $workspace "build/release-audit-$Version.json"
    $audit | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $destination -Encoding utf8
    [pscustomobject]$audit | Select-Object version,bytes,sha256,entries,resolverSha256 | ConvertTo-Json
} finally { $zip.Dispose() }
