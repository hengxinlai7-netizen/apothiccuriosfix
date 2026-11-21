param(
    [Parameter(Mandatory = $true)][string]$InstanceName,
    [Parameter(Mandatory = $true)][string]$LogName,
    [string]$QuickPlay = '',
    [string]$Props = '',
    [int]$TimeoutSec = 600,
    [string]$Xmx = '5G',
    [int]$GraceSec = 6
)

# Launches a real PCL instance (its own Forge version, libraries, assets, mods, config) straight into a copy of a
# world with --quickPlaySingleplayer, then stops it as soon as the data packs have been applied. Used to verify
# the installed jar + data pack against the actual mod set of an instance.
$ErrorActionPreference = 'Stop'
$ws = 'C:\Users\hengx\Documents\deepseek-harness\default-workspace'
$mc = "$env:APPDATA\.minecraft"
$versionDir = Join-Path $mc "versions\$InstanceName"
$json = Get-Content (Join-Path $versionDir "$InstanceName.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$libs = Join-Path $mc 'libraries'
$logs = Join-Path $ws 'logs'
$java = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot\bin\java.exe'
New-Item -ItemType Directory -Force $logs | Out-Null

# ---- a private copy of the world so the player's save is never touched -----
$worldCopy = '_acb_verify'
if ($QuickPlay -ne '') {
    $src = Join-Path $versionDir "saves\$QuickPlay"
    $dst = Join-Path $versionDir "saves\$worldCopy"
    if (!(Test-Path -LiteralPath $src)) { throw "missing world: $src" }
    if (Test-Path -LiteralPath $dst) { Remove-Item -LiteralPath $dst -Recurse -Force }
    Copy-Item -LiteralPath $src -Destination $dst -Recurse -Force
    Remove-Item (Join-Path $dst 'session.lock') -Force -ErrorAction SilentlyContinue
    Write-Output "world copy: $dst"
}

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
$cpEntries.Add((Join-Path $versionDir "$InstanceName.jar"))
foreach ($entry in $cpEntries) { if (!(Test-Path -LiteralPath $entry)) { throw "missing classpath entry: $entry" } }
$classpath = ($cpEntries) -join ';'

$map = @{
    'natives_directory'   = (Join-Path $versionDir "$InstanceName-natives")
    'launcher_name'       = 'acb'
    'launcher_version'    = '1'
    'classpath'           = $classpath
    'classpath_separator' = ';'
    'library_directory'   = $libs
    'version_name'        = $InstanceName
    'auth_player_name'    = 'ACBVerify'
    'auth_uuid'           = '00000000000040008000000000000002'
    'auth_access_token'   = '0'
    'clientid'            = '0'
    'auth_xuid'           = '0'
    'user_type'           = 'legacy'
    'version_type'        = 'release'
    'game_directory'      = $versionDir
    'assets_root'         = (Join-Path $mc 'assets')
    'assets_index_name'   = "$($json.assets)"
}

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

$jvmArgs = New-Object System.Collections.Generic.List[string]
$jvmArgs.Add("-Xmx$Xmx")
$jvmArgs.Add('-Dfile.encoding=UTF-8')
$jvmArgs.Add('-Dstdout.encoding=UTF-8')
if ($Props) { foreach ($p in $Props.Split(' ')) { if ($p) { $jvmArgs.Add($p) } } }
foreach ($a in (Expand-Args $json.arguments.jvm $map)) { $jvmArgs.Add($a) }

$gameArgs = New-Object System.Collections.Generic.List[string]
$gameArgs.Add($json.mainClass)
foreach ($a in (Expand-Args $json.arguments.game $map)) { $gameArgs.Add($a) }
$gameArgs.Add('--width'); $gameArgs.Add('854')
$gameArgs.Add('--height'); $gameArgs.Add('480')
if ($QuickPlay -ne '') { $gameArgs.Add('--quickPlaySingleplayer'); $gameArgs.Add($worldCopy) }

# back up the existing latest.log so the instance's own log stays available, then start from an empty one
$latest = Join-Path $versionDir 'logs\latest.log'
if (Test-Path $latest) {
    Copy-Item -LiteralPath $latest -Destination (Join-Path $logs "$LogName.previous.log") -Force
    Remove-Item -LiteralPath $latest -Force
}

$outLog = Join-Path $logs "$LogName.stdout.log"
$errLog = Join-Path $logs "$LogName.stderr.log"
foreach ($f in @($outLog, $errLog)) { if (Test-Path $f) { Remove-Item $f -Force } }

Write-Output ("launching $InstanceName (gameDir=$versionDir, java=$java, props='$Props')")
$argString = ($jvmArgs + $gameArgs) | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
$argString = $argString -join ' '
$proc = Start-Process -FilePath $java -ArgumentList $argString -WorkingDirectory $versionDir -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Write-Output "pid=$($proc.Id)"

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$state = 'timeout'
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $txt = ''
    if (Test-Path $latest) { $txt = Get-Content -LiteralPath $latest -Raw -Encoding UTF8 -ErrorAction SilentlyContinue }
    if ($txt -match 'Registered \d+ affixes') { $state = 'affixes-registered'; Start-Sleep -Seconds $GraceSec; break }
    # NOTE: other mods in these packs throw InvalidInjectionException among themselves (esoteric_spell_compat
    # vs irons_restrictions), so only this mod's own mixin failures count here.
    if ($txt -match 'Exception in thread "main"|Failed to create mod instance|Mixin apply for mod apothiccuriosfix failed') {
        $state = 'failure:' + $Matches[0]; break
    }
    if ($proc.HasExited) { $state = "exited:$($proc.ExitCode)"; break }
}
if ($state -eq 'affixes-registered' -and (Test-Path $latest)) {
    # the datapack warnings are all printed right after the affix registration; give them a moment
    Start-Sleep -Seconds 3
}
Write-Output "state=$state"

if (-not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}
if (Test-Path $latest) {
    Copy-Item -LiteralPath $latest -Destination (Join-Path $logs "$LogName.log") -Force
    Write-Output "copied latest.log -> $logs\$LogName.log"
}

# remove the world copy again
if ($QuickPlay -ne '' -and (Test-Path -LiteralPath $dst)) {
    Remove-Item -LiteralPath $dst -Recurse -Force -ErrorAction SilentlyContinue
    Write-Output "world copy removed"
}
