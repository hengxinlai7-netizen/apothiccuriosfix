param(
    [Parameter(Mandatory = $true)][string]$Version
)

# Builds the shipped jar plus the test variant used by the isolated server.
#   shipped : data/apothiccurios/** exactly as in the source tree (irons_spellbooks attributes included)
#   variant : same code, but spell_power/max_mana swapped for vanilla attributes, because the isolated test
#             server cannot load irons_spellbooks (its prerequisite fallen_lib needs Forge 47.4+)
$ErrorActionPreference = 'Stop'
$proj = 'C:\Users\hengx\Documents\deepseek-harness\default-workspace\apothiccurios-fix'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$env:GRADLE_USER_HOME = 'C:\Users\hengx\Documents\deepseek-harness\default-workspace\.gradle-home'
$gradle = 'C:\Users\hengx\Documents\deepseek-harness\default-workspace\.tools\gradle-8.5\bin\gradle.bat'
$modId = 'apothiccuriosfix'
$ns = 'apothiccurios'
$dataDir = Join-Path $proj "src\main\resources\data\$ns"
$stash = Join-Path $env:TEMP 'acf-data-stash'

function Invoke-Build {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $proj
    try {
        & $gradle --no-daemon --offline build 2>&1 | Select-String -Pattern 'BUILD|error:|\.java:' | ForEach-Object { $_.Line }
    } finally {
        Pop-Location
        $ErrorActionPreference = $previous
    }
}

Write-Output '--- shipped jar'
Invoke-Build
Copy-Item (Join-Path $proj "build\libs\$modId-$Version.jar") (Join-Path $proj "build\libs\$modId-$Version-shipped.jar") -Force

Write-Output '--- variant jar (vanilla-only attributes)'
if (Test-Path $stash) { Remove-Item $stash -Recurse -Force }
Move-Item $dataDir $stash
try {
    python (Join-Path $proj 'tools\gen_curio_affixes_plus.py') --no-irons | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $proj 'src\main\resources\data') | Out-Null
    Copy-Item (Join-Path $proj "datapack\curio_affixes_plus_selftest\data\$ns") $dataDir -Recurse -Force
    Invoke-Build
    Copy-Item (Join-Path $proj "build\libs\$modId-$Version.jar") (Join-Path $proj "build\libs\$modId-$Version-testvariant.jar") -Force
} finally {
    if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force }
    Move-Item $stash $dataDir
}
Copy-Item (Join-Path $proj "build\libs\$modId-$Version-shipped.jar") (Join-Path $proj "build\libs\$modId-$Version.jar") -Force

Get-ChildItem (Join-Path $proj 'build\libs') -Filter "*$Version*" | ForEach-Object { '{0,-46} {1,8}' -f $_.Name, $_.Length }
