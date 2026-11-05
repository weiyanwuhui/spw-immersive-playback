param([string]$ToolsRoot, [string]$Classes, [string]$Tests, [string]$JdkHome, [string]$HostJar)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot
$java=Join-Path $JdkHome 'bin/java.exe'
$stdlib=Join-Path $ToolsRoot 'kotlin-stdlib-2.4.20.jar'
& $java -cp "$ToolsRoot/kotlin-compiler-embeddable.jar;$stdlib;$HostJar;$ToolsRoot/annotations.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $stdlib -d $Tests "$PSScriptRoot/src/test/kotlin/fixtures/TimedRow.kt"
if($LASTEXITCODE -ne 0){throw 'Resolver fixture compilation failed'}
& (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 -proc:none --release 25 -cp "$Classes;$HostJar" -d $Tests "$PSScriptRoot/src/test/java/local/spw/host/HostResolverSelfTest.java" "$PSScriptRoot/src/test/java/local/spw/host/HookInternals.java"
if($LASTEXITCODE -ne 0){throw 'Resolver test compilation failed'}
& $java -ea --enable-native-access=ALL-UNNAMED -cp "$Tests;$Classes;$HostJar" local.spw.host.HostResolverSelfTest
if($LASTEXITCODE -ne 0){throw 'Resolver reobfuscation test failed'}
