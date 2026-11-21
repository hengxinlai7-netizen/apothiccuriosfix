param(
    [Parameter(Mandatory = $true)][string]$LogName,
    [string]$Jar = '',
    [string]$Props = '-Dapothiccuriosfix.clienttest=true',
    [int]$TimeoutSec = 420
)

# Launches the real Forge 1.20.1 client of the 勇者之章3 instance (its libraries/assets/version jar) with
# --gameDir pointed at a scratch directory whose mods/ holds only the baseline mods + the jar under test.
# Used to test the tooltip fix end to end: a dedicated server cannot render a tooltip.
$ErrorActionPreference = 'Stop'
$ws = (Split-Path $PSScriptRoot -Parent)
$mc = "$env:APPDATA\.minecraft"
$versionName = '勇者之章3'
$versionDir = Join-Path $mc "versions\$versionName"
$json = Get-Content (Join-Path $versionDir "$versionName.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$libs = Join-Path $mc 'libraries'
$gameDir = Join-Path $ws '.acb-client'
$logs = Join-Path $ws 'logs'
$java = (Join-Path $env:JAVA_HOME 'bin\java.exe')

New-Item -ItemType Directory -Force $logs | Out-Null

# ---- mods dir ---------------------------------------------------------------
$modsDir = Join-Path $gameDir 'mods'
if (Test-Path $gameDir) { Remove-Item $gameDir -Recurse -Force }
New-Item -ItemType Directory -Force $modsDir | Out-Null
$modNames = @(
    'Apotheosis-1.20.1-7.4.8.jar',
    'ApothicAttributes-1.20.1-1.3.7.jar',
    'Placebo-1.20.1-8.6.3.jar',
    'curios-forge-5.14.1+1.20.1.jar',
    'ApothicCurios-1.20.1-1.0.3e-optimized.jar'
)
foreach ($m in $modNames) {
    $src = Join-Path (Join-Path $versionDir 'mods') $m
    if (!(Test-Path -LiteralPath $src)) { throw "missing instance mod: $src" }
    Copy-Item -LiteralPath $src (Join-Path $modsDir $m) -Force
}
if ($Jar -ne '') {
    if (!(Test-Path -LiteralPath $Jar)) { throw "jar not found: $Jar" }
    Copy-Item -LiteralPath $Jar (Join-Path $modsDir (Split-Path $Jar -Leaf)) -Force
}
Write-Output ('mods: ' + ((Get-ChildItem $modsDir -Filter '*.jar' | ForEach-Object { $_.Name }) -join ', '))

# ---- classpath from the version json ---------------------------------------
function Test-Rules($rules) {
    if (-not $rules) { return $true }
    $allowed = $false
    foreach ($rule in $rules) {
        $match = $true
        if ($rule.os -and $rule.os.name -and $rule.os.name -ne 'windows') { $match = $false }
        if ($rule.features) { $match = $false }
        if ($match) { $allowed = ($rule.action -eq 'allow') }
    }
    return $allowed
}

$cpEntries = New-Object System.Collections.Generic.List[string]
foreach ($lib in $json.libraries) {
    if (-not (Test-Rules $lib.rules)) { continue }
    if (-not $lib.downloads -or -not $lib.downloads.artifact) { continue }
    $rel = $lib.downloads.artifact.path
    if (-not $rel) { continue }
    $cpEntries.Add((Join-Path $libs ($rel -replace '/', '\'))) 
}
$cpEntries.Add((Join-Path $versionDir "$versionName.jar"))
foreach ($entry in $cpEntries) { if (!(Test-Path -LiteralPath $entry)) { throw "missing classpath entry: $entry" } }
$classpath = ($cpEntries | ForEach-Object { $_ }) -join ';'

$natives = Join-Path $versionDir "$versionName-natives"

function Expand-Args($argsList, $map) {
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $argsList) {
        if ($entry -is [string]) {
            $value = $entry
            foreach ($key in $map.Keys) { $value = $value.Replace('${' + $key + '}', $map[$key]) }
            $out.Add($value)
        } elseif ($entry.value -is [string] -and (Test-Rules $entry.rules)) {
            $value = $entry.value
            foreach ($key in $map.Keys) { $value = $value.Replace('${' + $key + '}', $map[$key]) }
            $out.Add($value)
        }
    }
    return $out
}

$map = @{
    'natives_directory'   = $natives
    'launcher_name'       = 'acb'
    'launcher_version'    = '1'
    'classpath'           = $classpath
    'classpath_separator' = ';'
    'library_directory'   = $libs
    'version_name'        = $versionName
    'auth_player_name'    = 'TooltipTest'
    'auth_uuid'           = '00000000000040008000000000000001'
    'auth_access_token'   = '0'
    'clientid'            = '0'
    'auth_xuid'           = '0'
    'user_type'           = 'legacy'
    'version_type'        = 'release'
    'game_directory'      = $gameDir
    'assets_root'         = (Join-Path $mc 'assets')
    'assets_index_name'   = "$($json.assets)"
}

$jvmArgs = New-Object System.Collections.Generic.List[string]
$jvmArgs.Add('-Xmx4G')
$jvmArgs.Add('-Dfile.encoding=UTF-8')
$jvmArgs.Add('-Dstdout.encoding=UTF-8')
if ($Props) { foreach ($p in $Props.Split(' ')) { if ($p) { $jvmArgs.Add($p) } } }
foreach ($a in (Expand-Args $json.arguments.jvm $map)) { $jvmArgs.Add($a) }

$gameArgs = New-Object System.Collections.Generic.List[string]
$gameArgs.Add($json.mainClass)
foreach ($a in (Expand-Args $json.arguments.game $map)) { $gameArgs.Add($a) }
$gameArgs.Add('--width'); $gameArgs.Add('854')
$gameArgs.Add('--height'); $gameArgs.Add('480')

$outLog = Join-Path $logs "$LogName.stdout.log"
$errLog = Join-Path $logs "$LogName.stderr.log"
foreach ($f in @($outLog, $errLog)) { if (Test-Path $f) { Remove-Item $f -Force } }

Write-Output ("launching client (gameDir=$gameDir, java=$java)")
$argString = ($jvmArgs + $gameArgs) | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
$argString = $argString -join ' '
$proc = Start-Process -FilePath $java -ArgumentList $argString -WorkingDirectory $gameDir -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Write-Output "pid=$($proc.Id)"

$latest = Join-Path $gameDir 'logs\latest.log'
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$state = 'timeout'
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $txt = ''
    if (Test-Path $latest) { $txt = Get-Content -LiteralPath $latest -Raw -Encoding UTF8 -ErrorAction SilentlyContinue }
    if ($txt -match 'RESULT=CLIENTPASS') { $state = 'client-pass'; break }
    if ($txt -match 'RESULT=CLIENTFAIL') { $state = 'client-fail'; break }
    if ($txt -match 'Texture Manager|Sound engine started|Stopping!') {
        if ($txt -match 'RESULT=CLIENT') { continue }
    }
    if ($txt -match 'InvalidInjectionException|applyFailed|Failed to create mod instance|Exception in thread "main"') {
        $state = 'failure:' + $Matches[0]; break
    }
    if ($proc.HasExited) { $state = "exited:$($proc.ExitCode)"; break }
}
Write-Output "state=$state"

Start-Sleep -Seconds 2
if (-not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}
if (Test-Path $latest) {
    Copy-Item -LiteralPath $latest -Destination (Join-Path $logs "$LogName.log") -Force
    Write-Output "copied latest.log -> $logs\$LogName.log"
}
