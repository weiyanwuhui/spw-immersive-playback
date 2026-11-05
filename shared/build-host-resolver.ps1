param([string]$ToolsRoot, [string]$Classes, [string]$JdkHome)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot
$metadata=Join-Path $ToolsRoot 'kotlin-metadata-jvm-2.4.20.jar'
if((Get-FileHash -LiteralPath $metadata).Hash -ne '98D9B1847908ADFDE7A8B2D4CC35708D7DDCCAE0698CEC56BCB506BCF9392595'){throw 'Unexpected Kotlin Metadata library'}
& (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 -proc:none --release 25 -cp "$metadata;$ToolsRoot/kotlin-stdlib-2.4.20.jar" -d $Classes "$PSScriptRoot/src/main/java/local/spw/host/HostResolver.java"
if($LASTEXITCODE -ne 0){throw 'Host resolver compilation failed'}
$jar=[IO.Compression.ZipFile]::OpenRead($metadata)
try {
    foreach($entry in $jar.Entries){
        if($entry.Name -and ($entry.FullName.StartsWith('kotlin/metadata/') -or $entry.FullName.StartsWith('META-INF/services/'))){
            $target=Join-Path $Classes $entry.FullName
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
        }
    }
} finally { $jar.Dispose() }
New-Item -ItemType Directory -Path (Join-Path $Classes 'licenses') -Force|Out-Null
Copy-Item "$PSScriptRoot/resources/Kotlin-Metadata-LICENSE.txt" (Join-Path $Classes 'licenses/Kotlin-Metadata-LICENSE.txt')
Copy-Item "$PSScriptRoot/resources/Kotlin-Metadata-NOTICE.txt" (Join-Path $Classes 'licenses/Kotlin-Metadata-NOTICE.txt')
