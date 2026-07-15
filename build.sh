#!/usr/bin/env bash
# Pipeline profissional de build + versionamento (SemVer) — equivalente do build_release.ps1.
# Uso: ./build.sh <app> [--bump patch|minor|major] [--type release|debug|staging|beta|production] [--force] [--no-tag]
#   ./build.sh agent
#   ./build.sh mplayer --bump minor
#   ./build.sh agent --type debug --force --no-tag
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP="${1:-}"; shift || true
BUMP="patch"; TYPE="release"; FORCE=0; NOTAG=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bump) BUMP="$2"; shift 2;;
    --type) TYPE="$2"; shift 2;;
    --force) FORCE=1; shift;;
    --no-tag) NOTAG=1; shift;;
    *) echo "Argumento desconhecido: $1"; exit 1;;
  esac
done

declare -A MODULE LABEL APPID
MODULE[agent]=agent;          LABEL[agent]="ARGOS_Agent";    APPID[agent]="com.mupa.agent.argos"
MODULE[mplayer]=mplayer_renner; LABEL[mplayer]="MPlayer";    APPID[mplayer]="com.mupa.player.renner"
MODULE[player]=app;           LABEL[player]="MupaPlayer";    APPID[player]="com.mupa.player.enterprise"
MODULE[app]=app;              LABEL[app]="MupaPlayer";       APPID[app]="com.mupa.player.enterprise"
MODULE[launcher]=launcher;    LABEL[launcher]="ARGOS_Launcher"; APPID[launcher]="com.mupa.argos.launcher"
MODULE[browser]=browser;      LABEL[browser]="ARGOS_Browser";   APPID[browser]="com.mupa.argos.browser"
MODULE[remote]=remote;        LABEL[remote]="ARGOS_Remote";     APPID[remote]="com.mupa.argos.remote"

[[ -z "$APP" || -z "${MODULE[$APP]:-}" ]] && { echo "App invalido. Use: ${!MODULE[*]}"; exit 1; }
MOD="${MODULE[$APP]}"; LBL="${LABEL[$APP]}"; MODDIR="$ROOT/$MOD"
[[ -d "$MODDIR" ]] || { echo "[ERRO] Modulo :$MOD nao existe em $MODDIR"; exit 1; }

echo "==> Verificando git status"
if [[ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null || true)" ]]; then
  git -C "$ROOT" status --short | head -n 12
  if [[ "$FORCE" -ne 1 ]]; then
    read -r -p "    Deseja continuar? (s/N) " a
    [[ "$a" =~ ^[sSyY] ]] || { echo "Cancelado."; exit 1; }
  fi
fi

VP="$MODDIR/version.properties"
CUR_NAME="1.0.0"; CUR_CODE=0
[[ -f "$VP" ]] && {
  CUR_NAME="$(grep -E '^VERSION_NAME=' "$VP" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
  CUR_CODE="$(grep -E '^VERSION_CODE=' "$VP" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
}
[[ "$CUR_NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "VERSION_NAME invalido: $CUR_NAME"; exit 1; }
MAJ=${BASH_REMATCH[1]}; MIN=${BASH_REMATCH[2]}; PAT=${BASH_REMATCH[3]}
case "$BUMP" in
  major) MAJ=$((MAJ+1)); MIN=0; PAT=0;;
  minor) MIN=$((MIN+1)); PAT=0;;
  patch) PAT=$((PAT+1));;
esac
NEW_NAME="$MAJ.$MIN.$PAT"; NEW_CODE=$((CUR_CODE+1))
echo "==> Versao: $CUR_NAME (build $CUR_CODE) -> $NEW_NAME (build $NEW_CODE)"

cat > "$VP" <<EOF
# $LBL — single source of truth for versioning.
# Managed automatically by build.sh (Semantic Versioning).
VERSION_NAME=$NEW_NAME
VERSION_CODE=$NEW_CODE
EOF

GT="Release"; [[ "$TYPE" == "debug" ]] && GT="Debug"
GDIR="$(echo "$GT" | tr '[:upper:]' '[:lower:]')"
echo "==> Compilando :$MOD ($GT)"
if ! "$ROOT/gradlew" ":$MOD:assemble$GT"; then
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$CUR_NAME" "$CUR_CODE" > "$VP"
  echo "[ERRO] Build falhou; versao revertida."; exit 1
fi

APKDIR="$MODDIR/build/outputs/apk/$GDIR"
APK="$(ls -t "$APKDIR"/*.apk 2>/dev/null | grep -v unsigned | head -1 || ls -t "$APKDIR"/*.apk | head -1)"
[[ -n "$APK" ]] || { echo "Nenhum APK em $APKDIR"; exit 1; }

YYYY="$(date +%Y)"; MM="$(date +%m)"; OUTDIR="$ROOT/builds/$YYYY/$MM"; mkdir -p "$OUTDIR"
CH=""; [[ "$TYPE" == "staging" ]] && CH="-staging"; [[ "$TYPE" == "beta" ]] && CH="-beta"
BASE="${LBL}_v${NEW_NAME}_build${NEW_CODE}${CH}"
cp -f "$APK" "$OUTDIR/$BASE.apk"
HASH="$(sha256sum "$OUTDIR/$BASE.apk" | cut -d' ' -f1)"
echo "$HASH  $BASE.apk" > "$OUTDIR/$BASE.sha256"

if [[ -n "$(git -C "$ROOT" tag --list "$APP-v$NEW_NAME" 2>/dev/null || true)" ]]; then LAST=""; else LAST="$(git -C "$ROOT" describe --tags --abbrev=0 --match "$APP-v*" 2>/dev/null || true)"; fi
if [[ -n "$LAST" ]]; then COMMITS="$(git -C "$ROOT" log "$LAST..HEAD" --no-merges --pretty='* %s' -- "$MOD/" 2>/dev/null || true)"; else COMMITS="$(git -C "$ROOT" log -n 15 --no-merges --pretty='* %s' -- "$MOD/" 2>/dev/null || true)"; fi
[[ -n "$COMMITS" ]] || COMMITS="* Build $NEW_NAME (build $NEW_CODE)"

cat > "$OUTDIR/release_notes_$BASE.md" <<EOF
# $LBL

Versao: $NEW_NAME
Build: $NEW_CODE
Channel: $TYPE
Data: $(date '+%Y-%m-%d %H:%M')
ApplicationId: ${APPID[$APP]}
APK: $BASE.apk
SHA-256: $HASH

## Alteracoes
$COMMITS
EOF

CL="$MODDIR/CHANGELOG.md"
TMP="$(mktemp)"
{
  echo "# Changelog — $LBL"; echo
  echo "## [$NEW_NAME] - $(date +%Y-%m-%d)  (build $NEW_CODE, $TYPE)"; echo
  echo "$COMMITS"; echo
  [[ -f "$CL" ]] && grep -v '^# Changelog' "$CL" | sed '/^$/N;/^\n$/D'
} > "$TMP"
mv "$TMP" "$CL"

if [[ "$NOTAG" -ne 1 ]]; then
  git -C "$ROOT" tag -a "$APP-v$NEW_NAME" -m "$LBL $NEW_NAME (build $NEW_CODE)" 2>/dev/null && echo "Tag $APP-v$NEW_NAME criada (git push --tags)" || echo "[!] tag nao criada (commit a versao primeiro)"
fi

echo "────────────────────────────────────────────"
echo " RESUMO: $LBL  $CUR_NAME -> $NEW_NAME (build $CUR_CODE -> $NEW_CODE)"
echo " APK: builds/$YYYY/$MM/$BASE.apk"
echo " SHA-256: $HASH"
echo "────────────────────────────────────────────"
