# Especificação e Documentação Técnica: Resiliência de Mídias e Indicador Visual Pós-Inicialização (mPlayer)

**Data**: 28 de Setembro de 2026  
**Autor**: Logan / Antigravity Team  
**Módulos Afetados**: `:app` (`storage/db`, `managers`, `monitoring`, `ui`, `res/layout`, `res/drawable`), `supabase/migrations`

---

## 1. Contexto e Problema Original

Anteriormente, no fluxo de inicialização do mPlayer ([`PlayerActivity.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt)):
- O método `initialSyncAndPlayback` executava um loop `while (playlist.size < items.size)` sem limite de tentativas a cada 2,5 segundos.
- Quando uma mídia apontava para uma URL quebrada (HTTP 404), expirada no CDN ou com erro de conexão, o download falhava continuamente e a tela cheia permanecia bloqueada exibindo *"Baixando conteúdos... Faltando X de Y mídias"*.
- Mesmo que outras mídias do manifest já estivessem baixadas com sucesso no armazenamento local persistente (`/sdcard/Android/data/com.mupa.player.enterprise/files/media/`), elas não eram reproduzidas.
- Nas checagens horárias em segundo plano (`refreshInBackground`), a interface também acionava overlays na tela que podiam atrapalhar a leitura de preços e a experiência dos clientes no PDV.

---

## 2. Soluções e Arquitetura Implementada

### 2.1. Reprodução Parcial Imediata
- Ao concluir a primeira tentativa ou caso ao menos uma mídia já esteja salva em armazenamento local, o mPlayer inicia a exibição imediatamente (`playerEngine.start(playlist)`) e fecha o overlay de tela cheia (`setSyncOverlayVisible(false)`).
- O terminal fica liberado para operação e exibição de mídia desde o primeiro instante viável.

### 2.2. Fim do Loop Infinito e Política de Retries
- O loop de inicialização foi limitado a um teto de **3 tentativas** com intervalo de 2,5 segundos.
- Caso uma ou mais mídias persistam em falha após a 3ª tentativa:
  - O loop é encerrado com segurança.
  - A falha é registrada localmente para envio ao Supabase.
  - O player mantém a reprodução dos conteúdos válidos.
  - Novas tentativas para as mídias faltantes só ocorrerão no próximo ciclo horário de segundo plano (ou se um novo manifest for enviado ao dispositivo via comando).

### 2.3. Fallback de Armazenamento Local Vazio (Zero Mídias)
Se nenhuma mídia do novo manifest conseguir ser baixada:
1. O mPlayer tenta carregar e reproduzir mídias de um manifest anterior salvas no armazenamento local persistente (`tryStartOfflinePlayback()`).
2. Se o dispositivo for novo/formatado e o armazenamento local estiver 100% vazio:
   - Exibe a tela institucional com status claro e contagem regressiva em tempo real:
     > *"Não foi possível encontrar seus conteúdos, tentando novamente em 30 segundos"*
   - A contagem decresce segundo a segundo (30, 29, 28... 1) e, ao zerar, executa uma nova tentativa de sincronização com o servidor.

### 2.4. Relatório de Falhas no Supabase (Tabela Dedicada)
- Criada a entidade e tabela `media_download_failures`:
  - `id`: UUID (Primary Key)
  - `device_id`: TEXT (Serial/ID do dispositivo)
  - `tenant_id`: UUID (Preenchido automaticamente via trigger no Supabase com base no serial)
  - `media_id`: TEXT (ID da mídia)
  - `media_name`: TEXT (Nome da campanha/arquivo)
  - `url`: TEXT (URL que falhou)
  - `error_reason`: TEXT (Código HTTP ex: `http_404`, `empty_file`, etc.)
  - `created_at_epoch_ms`: BIGINT (Timestamp em milissegundos)
  - `created_at`: TIMESTAMPTZ (Data/hora UTC)
  - `uploadedAtEpochMs`: BIGINT (Controle local do Room para upload em lote)
- Criado o [`MediaDownloadFailureSyncManager.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/monitoring/MediaDownloadFailureSyncManager.kt):
  - Envia em lote (`POST /rest/v1/media_download_failures`) com `apikey` e Bearer Token.
  - Disparado no ciclo horário de telemetria junto aos outros relatórios do app (`AudienceSyncManager`, `PriceAnalyticsSyncManager`, `MediaPlayLogsSyncManager`, `DeviceEventSyncManager`).
- Script de migração para o Supabase criado em:
  [`supabase/migrations/20260928000000_create_media_download_failures.sql`](file:///c:/dev/mplayer/supabase/migrations/20260928000000_create_media_download_failures.sql).

### 2.5. Indicador Visual Pós-Inicialização (Sync Pill Minimalista)
- Nas sincronizações periódicas após a inicialização (quando o player já estiver rodando), a interface **nunca mais abre overlay de tela cheia nem exibe alertas de erro**.
- Foi implementado o componente flutuante **Sync Pill**:
  - Posicionamento: centralizado na borda inferior da tela (`bottom|center_horizontal`).
  - Visual: cantos arredondados (`24dp`), fundo escuro translúcido (`#E60F172A`) e stroke sutil ciano.
  - Conteúdo: micro spinner indicador, tipografia compacta (`11sp`) exibindo texto sutil (ex: *"Atualizando conteúdos... 65%"*) e barra de progresso horizontal (`3dp`).
  - Comportamento: surge com fade-in suave ao iniciar o download e desaparece com fade-out automático ao concluir.

---

## 3. Arquivos Criados e Modificados

| Arquivo | Ação | Descrição |
|---|---|---|
| [`CLAUDE.md`](file:///c:/dev/mplayer/CLAUDE.md) | Criado | Diretrizes para agentes Claude instruindo sobre a organização de documentação em `specs/` (assinado por Logan). |
| [`specs/debug-*.md`](file:///c:/dev/mplayer/specs/) | Movidos | 8 arquivos de investigação técnica anteriores movidos da raiz para a pasta padronizada `specs/`. |
| [`app/src/main/res/drawable/bg_sync_pill.xml`](file:///c:/dev/mplayer/app/src/main/res/drawable/bg_sync_pill.xml) | Criado | Drawable de fundo arredondado e estilizado para a pill flutuante. |
| [`app/src/main/res/layout/activity_player.xml`](file:///c:/dev/mplayer/app/src/main/res/layout/activity_player.xml) | Modificado | Adicionado o container `syncPillContainer` com barra e texto para sync discreto. |
| [`app/.../storage/db/MediaDownloadFailureEntity.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/storage/db/MediaDownloadFailureEntity.kt) | Criado | Entidade Room para armazenamento local das falhas de download. |
| [`app/.../storage/db/MediaDownloadFailureDao.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/storage/db/MediaDownloadFailureDao.kt) | Criado | DAO Room com queries de inserção, pendências e marcação de upload. |
| [`app/.../storage/db/AppDatabase.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/storage/db/AppDatabase.kt) | Modificado | Versão incrementada para 9, inclusão de `MediaDownloadFailureEntity`, DAO e migração `MIGRATION_8_9`. |
| [`app/.../monitoring/MediaDownloadFailureSyncManager.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/monitoring/MediaDownloadFailureSyncManager.kt) | Criado | Gerenciador de upload em lote para a API REST do Supabase. |
| [`supabase/migrations/20260928000000_create_media_download_failures.sql`](file:///c:/dev/mplayer/supabase/migrations/20260928000000_create_media_download_failures.sql) | Criado | Script DDL da tabela no Supabase com RLS e trigger de associação a `tenants`. |
| [`app/.../managers/ManifestManager.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/managers/ManifestManager.kt) | Modificado | Captura do motivo de falha e persistência automática no DAO `mediaDownloadFailureDao`. |
| [`app/.../ui/PlayerActivity.kt`](file:///c:/dev/mplayer/app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt) | Modificado | Lógica de retries (máx 3), reprodução parcial imediata, contagem regressiva de 30s para storage vazio, controle da Pill e agendamento horário do `MediaDownloadFailureSyncManager`. |

---

## 4. Como Executar a Migração no Supabase

No painel do Supabase (SQL Editor do projeto `iurqddkuihjsmxubibao`), execute o script presente em:
`supabase/migrations/20260928000000_create_media_download_failures.sql`
Isso criará a tabela com permissão de inserção para o token do app e vinculará automaticamente as falhas à empresa/tenant correspondente pelo serial do dispositivo.
