<#
.SYNOPSIS
  Pipeline profissional de build + versionamento (SemVer) para os apps ARGOS/Mupa.

.DESCRIPTION
  Lê a versão atual de version.properties, incrementa (SemVer), compila,
  renomeia o APK, move para builds/AAAA/MM, gera SHA-256, release_notes.md,
  atualiza o CHANGELOG.md, cria uma tag git e exibe um resumo.

.EXAMPLE
  .\build_release.ps1 agent
  .\build_release.ps1 agent -Bump minor
  .\build_release.ps1 mplayer -Bump major -Type release
  .\build_release.ps1 agent -Type debug -Force -NoTag

.PARAMETER App
  agent | mplayer | player | launcher | browser | remote

.PARAMETER Bump
  patch (padrao) | minor | major

.PARAMETER Type
  release (padrao) | debug | staging | beta | production
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $App,

    [ValidateSet("patch", "minor", "major")]
    [string] $Bump = "patch",

    [ValidateSet("release", "debug", "staging", "beta", "production")]
    [string] $Type = "release",

    [switch] $Force,
    [switch] $NoTag
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# -- Helpers -----------------------------------------------------------------
function Write-Step($msg)  { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host "    [!]  $msg" -ForegroundColor Yellow }
function Fail($msg)        { Write-Host "`n[ERRO] $msg" -ForegroundColor Red; exit 1 }

function Write-Utf8NoBom([string]$path, [string]$content) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $enc)
}

# -- App registry ------------------------------------------------------------
$apps = @{
    "agent"    = @{ Module = "agent";          Label = "ARGOS_Agent";    AppId = "com.mupa.agent.argos" }
    "mplayer"  = @{ Module = "mplayer_renner"; Label = "MPlayer";        AppId = "com.mupa.player.renner" }
    "player"   = @{ Module = "app";            Label = "MupaPlayer";     AppId = "com.mupa.player.enterprise" }
    "app"      = @{ Module = "app";            Label = "MupaPlayer";     AppId = "com.mupa.player.enterprise" }
    "launcher" = @{ Module = "launcher";       Label = "ARGOS_Launcher"; AppId = "com.mupa.argos.launcher" }
    "browser"  = @{ Module = "browser";        Label = "ARGOS_Browser";  AppId = "com.mupa.argos.browser" }
    "remote"   = @{ Module = "remote";         Label = "ARGOS_Remote";   AppId = "com.mupa.argos.remote" }
}

$key = $App.ToLower()
if (-not $apps.ContainsKey($key)) {
    Fail "App desconhecido '$App'. Use: $($apps.Keys -join ', ')"
}
$cfg = $apps[$key]
$module = $cfg.Module
$label  = $cfg.Label
$moduleDir = Join-Path $root $module

Write-Host "ARGOS Build Pipeline" -ForegroundColor Magenta
Write-Host "App: $label  |  Modulo: :$module  |  Bump: $Bump  |  Type: $Type" -ForegroundColor Gray

if (-not (Test-Path $moduleDir)) {
    Fail "Modulo ':$module' nao encontrado em $moduleDir. (App ainda nao existe no projeto)"
}

# -- 1. Git status ------------------------------------------------------------
Write-Step "Verificando git status"
$gitDirty = $false
try {
    $st = git -C $root status --porcelain 2>$null
    if ($LASTEXITCODE -eq 0 -and $st) { $gitDirty = $true }
} catch { Write-Warn2 "git nao disponivel - pulando verificacao." }

if ($gitDirty) {
    Write-Warn2 "Existem alteracoes nao commitadas:"
    (git -C $root status --short) | Select-Object -First 12 | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkYellow }
    if (-not $Force) {
        $ans = Read-Host "    Deseja continuar mesmo assim? (s/N)"
        if ($ans -notmatch '^[sSyY]') { Fail "Cancelado pelo usuario." }
    } else {
        Write-Warn2 "-Force ativo: continuando com working tree sujo."
    }
} else {
    Write-Ok "Working tree limpo."
}

# -- 2/3. Ler + incrementar versao -------------------------------------------
Write-Step "Lendo versao atual (version.properties)"
$vpPath = Join-Path $moduleDir "version.properties"
$curName = $null; $curCode = $null
if (Test-Path $vpPath) {
    Get-Content $vpPath | ForEach-Object {
        if ($_ -match '^\s*VERSION_NAME\s*=\s*(.+?)\s*$') { $curName = $Matches[1] }
        if ($_ -match '^\s*VERSION_CODE\s*=\s*(\d+)\s*$')  { $curCode = [int]$Matches[1] }
    }
}
if (-not $curName) { $curName = "1.0.0" }
if ($null -eq $curCode) { $curCode = 0 }

if ($curName -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    Fail "VERSION_NAME invalido '$curName' (esperado MAJOR.MINOR.PATCH)."
}
$maj = [int]$Matches[1]; $min = [int]$Matches[2]; $pat = [int]$Matches[3]

switch ($Bump) {
    "major" { $maj++; $min = 0; $pat = 0 }
    "minor" { $min++; $pat = 0 }
    "patch" { $pat++ }
}
$newName = "$maj.$min.$pat"
$newCode = $curCode + 1

Write-Ok "Versao: $curName (build $curCode)  ->  $newName (build $newCode)"

# Persistir version.properties
$vpContent = @"
# $label - single source of truth for versioning.
# Managed automatically by build_release.ps1 (Semantic Versioning).
# VERSION_CODE must always increase and never repeat.
VERSION_NAME=$newName
VERSION_CODE=$newCode
"@
Write-Utf8NoBom $vpPath $vpContent
Write-Ok "version.properties atualizado."

# -- 4. Build -----------------------------------------------------------------
$gradleType = if ($Type -eq "debug") { "Debug" } else { "Release" }   # staging/beta/prod -> Release
$gradleDir  = $gradleType.ToLower()
Write-Step "Compilando :$module ($gradleType)"
$gradlew = Join-Path $root "gradlew.bat"
if (-not (Test-Path $gradlew)) { Fail "gradlew.bat nao encontrado em $root" }

# NOTE: do NOT pipe `2>&1` from a native exe under PS 5.1 (wraps stderr as terminating errors).
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$gradleTask = ":${module}:assemble${gradleType}"
& $gradlew $gradleTask
$buildExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($buildExit -ne 0) {
    # Restaurar versao em caso de falha
    Write-Utf8NoBom $vpPath ($vpContent -replace "VERSION_NAME=$newName", "VERSION_NAME=$curName" -replace "VERSION_CODE=$newCode", "VERSION_CODE=$curCode")
    Fail "Build falhou (exit $buildExit). Versao revertida para $curName (build $curCode)."
}
Write-Ok "Build concluido."

# -- 5/6. Localizar APK -------------------------------------------------------
Write-Step "Localizando APK gerado"
# When the module uses product flavors the APKs land in sub-folders like
# build/outputs/apk/<flavor>/release/ instead of build/outputs/apk/release/.
# Search recursively so both layouts work; prefer signed (non-unsigned) APKs
# and pick the most recently written one to avoid stale leftovers from
# earlier builds that used a different flavor structure.
$apkRootDir = Join-Path $moduleDir "build\outputs\apk"
if (-not (Test-Path $apkRootDir)) { Fail "Pasta de saida nao encontrada: $apkRootDir" }
$apk = Get-ChildItem -Path $apkRootDir -Filter "*${gradleDir}*.apk" -File -Recurse |
       Where-Object { $_.FullName -notmatch "\\unsigned\\" } |
       Sort-Object @{ Expression = { $_.Name -notmatch 'unsigned' }; Descending = $true }, LastWriteTime -Descending |
       Select-Object -First 1
if (-not $apk) { Fail "Nenhum APK encontrado em $apkRootDir" }
Write-Ok "APK: $($apk.Name)"

# -- 7. Renomear + mover para builds/AAAA/MM ---------------------------------
Write-Step "Organizando saida em builds/"
$now = Get-Date
$yyyy = $now.ToString("yyyy"); $mm = $now.ToString("MM")
$outDir = Join-Path $root "builds\$yyyy\$mm"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$channel = switch ($Type) { "staging" { "-staging" } "beta" { "-beta" } default { "" } }
$baseName = "${label}_v${newName}_build${newCode}${channel}"
$apkOut = Join-Path $outDir "$baseName.apk"
Copy-Item -Path $apk.FullName -Destination $apkOut -Force
Write-Ok "APK -> $apkOut"

# -- 8. Checksum SHA-256 ------------------------------------------------------
Write-Step "Gerando SHA-256"
$hash = (Get-FileHash -Algorithm SHA256 -Path $apkOut).Hash.ToLower()
$shaPath = Join-Path $outDir "$baseName.sha256"
Write-Utf8NoBom $shaPath "$hash  $baseName.apk`n"
Write-Ok "SHA-256: $hash"

# -- 9. Release notes (a partir dos commits desde a ultima tag) --------------
Write-Step "Gerando release_notes.md"
$lastTag = ""
try { $lastTag = (git -C $root describe --tags --abbrev=0 --match "$key-v*" 2>$null) } catch {}
$commits = @()
try {
    if ($lastTag) {
        $commits = git -C $root log "$lastTag..HEAD" --no-merges --pretty=format:"* %s" -- "$module/" 2>$null
    } else {
        $commits = git -C $root log -n 15 --no-merges --pretty=format:"* %s" -- "$module/" 2>$null
    }
} catch {}
if (-not $commits) { $commits = @("* Build $newName (build $newCode)") }

$apkSize = "{0:N1} MB" -f ($apk.Length / 1MB)
$notes = @"
# $label

Versao: $newName
Build: $newCode
Channel: $Type
Data: $($now.ToString("yyyy-MM-dd HH:mm"))
ApplicationId: $($cfg.AppId)
APK: $baseName.apk ($apkSize)
SHA-256: $hash

## Alteracoes
$($commits -join "`n")
"@
$notesPath = Join-Path $outDir "release_notes_$baseName.md"
Write-Utf8NoBom $notesPath $notes
Write-Ok "release_notes -> $notesPath"

# -- 10. CHANGELOG.md (por app) ----------------------------------------------
Write-Step "Atualizando CHANGELOG.md"
$changelogPath = Join-Path $moduleDir "CHANGELOG.md"
$header = "# Changelog - $label`n`nTodas as alteracoes relevantes deste app.`nFormato baseado em Keep a Changelog + SemVer.`n"
$existing = ""
if (Test-Path $changelogPath) {
    $existing = Get-Content $changelogPath -Raw
    $existing = $existing -replace [regex]::Escape($header), ""
}
$entry = @"
## [$newName] - $($now.ToString("yyyy-MM-dd"))  (build $newCode, $Type)

$($commits -join "`n")

"@
Write-Utf8NoBom $changelogPath ($header + "`n" + $entry + $existing)
Write-Ok "CHANGELOG.md atualizado."

# -- 11. Git tag --------------------------------------------------------------
$tag = "$key-v$newName"
if (-not $NoTag) {
    Write-Step "Criando tag git: $tag"
    try {
        $exists = git -C $root tag --list $tag 2>$null
        if ($exists) {
            Write-Warn2 "Tag $tag ja existe - pulando."
        } else {
            git -C $root tag -a $tag -m "$label $newName (build $newCode)" 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { Write-Ok "Tag $tag criada (lembre de: git push --tags)." }
            else { Write-Warn2 "Nao foi possivel criar a tag (commit a versao primeiro)." }
        }
    } catch { Write-Warn2 "git tag falhou: $_" }
} else {
    Write-Warn2 "-NoTag: tag git nao criada."
}

# -- 12. Resumo ---------------------------------------------------------------
Write-Host "`n--------------------------------------------" -ForegroundColor DarkCyan
Write-Host " RESUMO DO BUILD" -ForegroundColor Cyan
Write-Host "--------------------------------------------" -ForegroundColor DarkCyan
Write-Host (" App          : {0}" -f $label)
Write-Host (" Versao       : {0} -> {1}" -f $curName, $newName) -ForegroundColor Green
Write-Host (" Build code   : {0} -> {1}" -f $curCode, $newCode) -ForegroundColor Green
Write-Host (" Channel/Type : {0}" -f $Type)
Write-Host (" APK          : {0}" -f (Resolve-Path $apkOut -Relative))
Write-Host (" SHA-256      : {0}" -f $hash)
Write-Host (" Release notes: {0}" -f (Resolve-Path $notesPath -Relative))
Write-Host (" CHANGELOG    : {0}" -f (Resolve-Path $changelogPath -Relative))
Write-Host (" Tag git      : {0}" -f $(if ($NoTag) { "(pulada)" } else { $tag }))
Write-Host "--------------------------------------------`n" -ForegroundColor DarkCyan
Write-Host "Pronto para OTA / Cloudflare R2." -ForegroundColor Magenta
