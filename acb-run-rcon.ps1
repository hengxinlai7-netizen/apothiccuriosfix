param(
    [Parameter(Mandatory = $true)][string]$LogName,
    [string]$Jar = '',
    [string[]]$Commands = @('acf diag item apotheosis:potion_charm', 'acf selftest'),
    [string]$Props = '',
    [int]$TimeoutSec = 300
)

# Starts the isolated dedicated server (.prodserver-acf) with RCON enabled, waits for "Done (", sends the
# commands through RCON, prints the answers and stops the server with "stop". No game client is involved.
$ErrorActionPreference = 'Stop'
$root = (Split-Path $PSScriptRoot -Parent)
$dir = Join-Path $root '.prodserver-acf'
$mods = Join-Path $dir 'mods'
$baseline = Join-Path $root '.prodserver\mods'
$proj = Join-Path $root 'apothiccurios-fix'
$rconPassword = 'acbverify'
$rconPort = 25575

# --- server.properties: enable rcon for this run -----------------------------
$propsPath = Join-Path $dir 'server.properties'
$text = Get-Content -LiteralPath $propsPath -Raw -Encoding UTF8
$text = [regex]::Replace($text, 'enable-rcon=.*', 'enable-rcon=true')
$text = [regex]::Replace($text, 'rcon\.port=.*', "rcon.port=$rconPort")
$text = [regex]::Replace($text, 'rcon\.password=.*', "rcon.password=$rconPassword")
if ($text -notmatch 'rcon\.password=') { $text += "`nrcon.password=$rconPassword`n" }
Set-Content -LiteralPath $propsPath -Value $text -Encoding UTF8 -NoNewline

# --- world reset + mods ------------------------------------------------------
if (Test-Path (Join-Path $dir 'world')) { Remove-Item (Join-Path $dir 'world') -Recurse -Force }
Copy-Item (Join-Path $dir 'world_base') (Join-Path $dir 'world') -Recurse -Force
Remove-Item (Join-Path $dir 'logs\apothicuriosfix-diag.log') -Force -ErrorAction SilentlyContinue

Get-ChildItem $mods -Filter '*.jar' | Remove-Item -Force
foreach ($n in @('Apotheosis-1.20.1-7.4.8.jar', 'ApothicAttributes-1.20.1-1.3.7.jar',
                 'curios-forge-5.14.1+1.20.1.jar', 'Patchouli-1.20.1-85-FORGE.jar',
                 'Placebo-1.20.1-8.6.3.jar', 'simulatedspawner-1.7.7.jar')) {
    Copy-Item (Join-Path $baseline $n) (Join-Path $mods $n) -Force
}
Copy-Item (Join-Path $proj 'libs\ApothicCurios-1.20.1-1.0.3e-optimized.jar') $mods -Force
if ($Jar -ne '') {
    if (!(Test-Path -LiteralPath $Jar)) { throw "jar not found: $Jar" }
    Copy-Item -LiteralPath $Jar (Join-Path $mods (Split-Path $Jar -Leaf)) -Force
}
Write-Output ('mods: ' + ((Get-ChildItem $mods -Filter '*.jar' | ForEach-Object { $_.Name }) -join ', '))

# --- launch -----------------------------------------------------------------
$log = Join-Path $dir $LogName
$err = "$log.err"
foreach ($f in @($log, $err)) { if (Test-Path $f) { Remove-Item $f -Force } }
$javaArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')
if ($Props) { $javaArgs += $Props.Split(' ') }
$javaArgs += @('@user_jvm_args.txt', '@libraries/net/minecraftforge/forge/1.20.1-47.2.20/win_args.txt', 'nogui')
$proc = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $dir -PassThru -NoNewWindow `
    -RedirectStandardOutput $log -RedirectStandardError $err
Write-Output "pid=$($proc.Id)"

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$ready = $false
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { break }
    if (Test-Path $log) {
        $t = Get-Content $log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($t -and $t.Contains('Done (')) { $ready = $true; break }
        if ($t -and ($t.Contains('Failed to start') -or $t.Contains('A fatal error'))) { break }
    }
    Start-Sleep -Milliseconds 750
}
Write-Output "ready=$ready"
if ($ready) {
    Start-Sleep -Seconds 3
    $py = 'python'
    & $py (Join-Path $proj 'tools\rcon_run.py') $rconPort $rconPassword @Commands
    & $py (Join-Path $proj 'tools\rcon_run.py') $rconPort $rconPassword 'stop' | Out-Null
    Start-Sleep -Seconds 5
}
if (-not $proc.HasExited) {
    try { Stop-Process -Id $proc.Id -Force } catch { }
    Start-Sleep -Seconds 2
}
Write-Output "log: $log"
$diag = Join-Path $dir 'logs\apothicuriosfix-diag.log'
if (Test-Path $diag) {
    Write-Output "--- logs\apothicuriosfix-diag.log ---"
    Get-Content $diag -Encoding UTF8 | Select-Object -First 80
} else {
    Write-Output 'no diag log was written'
}
