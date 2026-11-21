param(
    [Parameter(Mandatory = $true)][string]$LogName,
    [string]$Props = '',
    [string[]]$ExtraMods = @(),
    [int]$TimeoutSec = 300,
    [int]$GraceSec = 0
)

# Runs the isolated 6-jar-baseline test server (.prodserver-acf) with the given extra mods and JVM props.
# The world is reset from world_base before every run so that each run loads the same data pack.
$ErrorActionPreference = 'Stop'
$root = (Split-Path $PSScriptRoot -Parent)
$dir = Join-Path $root '.prodserver-acf'
$mods = Join-Path $dir 'mods'
$baseline = (Join-Path (Split-Path $PSScriptRoot -Parent) '.prodserver\mods')

# --- world reset -------------------------------------------------------------
if (Test-Path (Join-Path $dir 'world')) { Remove-Item (Join-Path $dir 'world') -Recurse -Force }
Copy-Item (Join-Path $dir 'world_base') (Join-Path $dir 'world') -Recurse -Force

# --- mods: baseline 6 + extras ----------------------------------------------
Get-ChildItem $mods -Filter '*.jar' | Remove-Item -Force
$six = @(
    'Apotheosis-1.20.1-7.4.8.jar',
    'ApothicAttributes-1.20.1-1.3.7.jar',
    'curios-forge-5.14.1+1.20.1.jar',
    'Patchouli-1.20.1-85-FORGE.jar',
    'Placebo-1.20.1-8.6.3.jar',
    'simulatedspawner-1.7.7.jar'
)
foreach ($n in $six) { Copy-Item (Join-Path $baseline $n) (Join-Path $mods $n) -Force }
foreach ($m in $ExtraMods) {
    if (!(Test-Path $m)) { throw "missing extra mod: $m" }
    Copy-Item $m (Join-Path $mods (Split-Path $m -Leaf)) -Force
}
Write-Output ('mods: ' + ((Get-ChildItem $mods -Filter '*.jar' | ForEach-Object { $_.Name }) -join ', '))

# --- launch -----------------------------------------------------------------
$log = Join-Path $dir $LogName
$err = "$log.err"
foreach ($f in @($log, $err)) { if (Test-Path $f) { Remove-Item $f -Force } }

$javaArgs = @()
if ($Props) { $javaArgs += $Props.Split(' ') }
$javaArgs += @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '@user_jvm_args.txt',
    '@libraries/net/minecraftforge/forge/1.20.1-47.2.20/win_args.txt',
    'nogui'
)
Write-Output ('java ' + ($javaArgs -join ' '))
$proc = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $dir -PassThru -NoNewWindow `
    -RedirectStandardOutput $log -RedirectStandardError $err

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$markers = @('Done (', 'RESULT=', 'Failed to start', 'A fatal error', 'Exception in thread "main"')
$hit = $null
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { break }
    if (Test-Path $log) {
        $text = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($text) {
            foreach ($m in $markers) {
                if ($text.Contains($m)) { $hit = $m; break }
            }
        }
    }
    if ($hit) { break }
    Start-Sleep -Milliseconds 750
}
if ($hit) {
    Write-Output "marker hit: $hit"
    if ($GraceSec -gt 0) { Start-Sleep -Seconds $GraceSec }
} else {
    Write-Output 'timeout or exit without marker'
}
if (-not $proc.HasExited) {
    try { Stop-Process -Id $proc.Id -Force -ErrorAction Stop; Write-Output 'server process stopped (forced)' } catch { }
    Start-Sleep -Seconds 2
} else {
    Write-Output ("server exited, code=" + $proc.ExitCode)
}
Write-Output "log: $log"
