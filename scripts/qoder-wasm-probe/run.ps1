param(
  [Parameter(Mandatory=$true)][string]$SourceDirectory,
  [string]$NodeExecutable = 'node.exe',
  [string]$MavenExecutable = 'mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
$probe = $PSScriptRoot
$output = Join-Path $probe 'target/probe'
$wasm = Join-Path $source 'qoder-auth-wasm.wasm'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& $NodeExecutable --experimental-transform-types (Join-Path $probe 'reference.mjs') $source $output
if ($LASTEXITCODE -ne 0) { throw 'Node reference failed' }
& $MavenExecutable -q -f (Join-Path $probe 'pom.xml') compile exec:java '-Dexec.mainClass=probe.QoderProbe' "-Dexec.args=`"$wasm`" `"$output`"" '-Dgpg.skip'
if ($LASTEXITCODE -ne 0) { throw 'Java parity probe failed' }
& $NodeExecutable --experimental-transform-types (Join-Path $probe 'verify-java.mjs') $output
if ($LASTEXITCODE -ne 0) { throw 'Node cross-decryption failed' }
