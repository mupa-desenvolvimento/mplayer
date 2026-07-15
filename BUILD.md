# Sistema de Build & Versionamento — ARGOS / Mupa

Pipeline profissional de build seguindo **Semantic Versioning (SemVer)**, inspirado em
Android Gradle, Fastlane, GitHub Actions e Google Play.

O desenvolvedor executa **um único comando** e todo o resto é automático:
versionamento → build → renomeação → SHA-256 → release notes → changelog → tag git.

---

## Uso rápido

### Windows (PowerShell)
```powershell
.\build_release.ps1 agent                       # patch: 1.0.8 -> 1.0.9 (release)
.\build_release.ps1 agent -Bump minor           # 1.0.x -> 1.1.0
.\build_release.ps1 mplayer -Bump major         # 1.x -> 2.0.0
.\build_release.ps1 agent -Type debug -Force -NoTag
```

### Linux / macOS / CI (bash)
```bash
./build.sh agent
./build.sh mplayer --bump minor
./build.sh agent --type debug --force --no-tag
```

---

## Parâmetros

| Param | Valores | Padrão | Descrição |
|-------|---------|--------|-----------|
| App (posicional) | `agent` `mplayer` `player` `launcher` `browser` `remote` | — | App a buildar |
| `-Bump` / `--bump` | `patch` `minor` `major` | `patch` | Tipo de incremento SemVer |
| `-Type` / `--type` | `release` `debug` `staging` `beta` `production` | `release` | Canal/tipo de build |
| `-Force` / `--force` | flag | off | Ignora aviso de git sujo |
| `-NoTag` / `--no-tag` | flag | off | Não cria a tag git |

> `staging`, `beta` e `production` usam o build Gradle **Release** e adicionam um sufixo de canal no nome do APK (`-staging`, `-beta`).

---

## O que o script faz

1. **Git status** — avisa se há alterações não commitadas e pergunta se deseja continuar.
2. **Lê a versão atual** de `<app>/version.properties` (fonte única da verdade).
3. **Incrementa** `VERSION_NAME` (SemVer) e `VERSION_CODE` (+1, sempre crescente, nunca repete).
4. **Compila** `:<modulo>:assembleRelease` (ou Debug).
5. **Renomeia** o APK para o padrão `Label_vX.Y.Z_buildN.apk`.
6. **Move** para `builds/AAAA/MM/`.
7. **Gera SHA-256** (`.sha256`) — para OTA / rollback / validação de integridade.
8. **Gera `release_notes_*.md`** com versão, build, data, applicationId, SHA e os commits desde a última tag.
9. **Atualiza o `CHANGELOG.md`** do app.
10. **Cria a tag git** `<app>-vX.Y.Z` (a menos que `-NoTag`).
11. **Exibe um resumo**.

Se o build falhar, a versão é **revertida** automaticamente.

---

## Versionamento (SemVer)

```
MAJOR.MINOR.PATCH
  │     │     └── correções de bugs   (patch)
  │     └──────── novos recursos      (minor)
  └────────────── grandes mudanças    (major)
```

`VERSION_CODE` é sempre incremental (1, 2, 3, …) e **nunca** se repete — requisito do Google Play / Intune / OTA.

A fonte da verdade é o arquivo `version.properties` de cada app, lido pelo `build.gradle.kts`:

```properties
VERSION_NAME=1.0.9
VERSION_CODE=10
```

---

## Saída

```
builds/
  2026/
    06/
      ARGOS_Agent_v1.0.9_build10.apk
      ARGOS_Agent_v1.0.9_build10.sha256
      release_notes_ARGOS_Agent_v1.0.9_build10.md
```

Os binários (`*.apk`, `*.aab`, `*.sha256`) são ignorados pelo git (`builds/.gitignore`).

---

## Apps registrados

| Chave | Módulo Gradle | Label do APK | applicationId |
|-------|---------------|--------------|----------------|
| `agent`   | `:agent`          | `ARGOS_Agent`     | `com.mupa.agent.argos` |
| `mplayer` | `:mplayer_renner` | `MPlayer`         | `com.mupa.player.renner` |
| `player`  | `:app`            | `MupaPlayer`      | `com.mupa.player.enterprise` |
| `launcher`| `:launcher` *(futuro)* | `ARGOS_Launcher` | `com.mupa.argos.launcher` |
| `browser` | `:browser` *(futuro)*  | `ARGOS_Browser`  | `com.mupa.argos.browser` |
| `remote`  | `:remote` *(futuro)*   | `ARGOS_Remote`   | `com.mupa.argos.remote` |

Para adicionar um app, registre-o no mapa em `build_release.ps1` / `build.sh` e crie o módulo.

---

## Próximos passos (roadmap)

- [ ] Publicar APK automaticamente no Cloudflare R2
- [ ] Criar deployment OTA no ARGOS Web ao final do build
- [ ] Rollout gradual + rollback usando o SHA-256
- [ ] Integração com GitHub Actions (CI) usando `build.sh`
