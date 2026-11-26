# Mupa Locutor — arquitetura, análise de licenças e plano de implementação

> Nota de organização do projeto (ver `CLAUDE.md`): toda documentação nova
> vai em `specs/`. Este documento segue esse padrão.

Entregável da Etapa 0 do pedido "PROJETO MUPA LOCUTOR" (motor de locução
offline para os terminais de consulta de preço) — **arquitetura, análise
de licenças e plano de implementação, antes de qualquer código**, conforme
pedido explícito na seção 12 (Metodologia) do briefing original.

Nenhum código foi escrito ainda. Este documento existe pra ser aprovado
(ou ajustado) antes da Fase 1 começar.

---

## 1. Onde isso se encaixa na arquitetura atual (reaproveitar, não reinventar)

O `mplayer` **já tem** boa parte do que o briefing pede, testado em
produção. Importante não duplicar:

### 1.1 Já existe um "Speech Text Formatter" de verdade (seção 5 do briefing)

`OfferIntelligence.buildSpokenPrice()` (`app/.../price/OfferIntelligence.kt:196`)
já faz exatamente a conversão monetária pedida — "R$ 7,99" →
"sete reais e noventa e nove centavos", com singular/plural e suporte a
unidade customizada (já usado para dólar/peso em conversão de moeda
estrangeira). `OfferIntelligence.analyze()` já monta as frases de oferta
("Leve 3, pague 2", "De X por Y", "Preço exclusivo para clientes do
clube", etc.) e `PlayerActivity.speakSlotsIfPossible()`/`speakPriceIfPossible()`
já decidem qual frase final falar, com dedup (não repete o mesmo EAN em
<3,5s) e ordenação (oferta antes do preço cheio).

**Decisão de arquitetura**: o Mupa Locutor **não deve reimplementar** o
SpeechTextFormatter do zero. Ele entra como um novo **backend de síntese**
por trás do texto que esse código já produz — o texto continua vindo do
mesmo lugar de sempre (e continua precisando bater com o que aparece
escrito na tela, mesma regra de hoje).

### 1.2 Já existe uma cadeia de fallback de 3 níveis — o offline entra como um novo nível

Hoje (`PriceVoiceSynth` + `speakWithVoiceSynth`):
1. Cache local (arquivo `.mp3` já sintetizado) → toca na hora, zero rede.
2. Cache miss → fala **imediatamente** com o TTS nativo do Android
   (`speakNative`, fallback síncrono, nunca espera rede) e dispara busca em
   segundo plano (tabela Supabase → Edge Function Azure) só pra cachear
   pra próxima vez.
3. TTS nativo também indisponível (TV boxes sem motor nenhum, ex. K11
   rk3576) → loja fica muda nesse caso raro.

O Mupa Locutor **substitui o papel do nível 1+2 (Azure)**, não o
conceito da cadeia: a ideia central do pedido é tirar a dependência de
rede/Azure, então o motor offline deveria ser candidato a **substituir
inteiramente** `PriceVoiceSynth`, com o TTS nativo do Android continuando
como único fallback de último caso (se o Mupa Locutor falhar por
qualquer motivo — modelo corrompido, engine não inicializou, etc.) Isso é
consistente com a regra não-negociável do `constitution.md` deste projeto:
**Fail-Safe Playback** — motor de voz não pode nunca travar ou crashar o
app, só degradar.

---

## 2. Análise de licenças — ACHADO CRÍTICO, leia antes de prosseguir

Pesquisado ao vivo (GitHub + Hugging Face, 2026-11-17). Resumo direto: **o
engine Piper e o binding Android sugeridos no briefing são GPL-3.0, e o
binding sugerido (`piper-kotlin`) os vincula DENTRO do processo do app via
JNI** — isso é uma obrigação de copyleft real sobre um app comercial
fechado, não um detalhe formal.

### 2.1 `OHF-Voice/piper1-gpl` (motor Piper)

- **Licença: GPL-3.0-or-later** (`COPYING` = texto integral da GPLv3;
  `docs/LICENSING.md`: *"The Piper **engine** is GPL-3.0-or-later."*)
- O nome do repo (`piper1-gpl`) é proposital — confirma que é uma versão
  GPL do motor, não um detalhe de nomenclatura.
- **Embute o espeak-ng pra fonemização** (*"A fast and local neural
  text-to-speech engine that embeds espeak-ng for phonemization"*) —
  espeak-ng também é GPL-3.0. Ou seja, mesmo se existisse uma forma de
  evitar o copyleft do Piper em si, o espeak-ng embutido traria o mesmo
  problema.
- `COPYING`: *"The GNU General Public License does not permit
  incorporating your program into proprietary programs."*

### 2.2 `piper-tts-apps/piper-kotlin` (binding Android sugerido no briefing)

- **Também GPL-3.0-or-later** (LICENSE = GPLv3 completa).
- **Mecanismo: JNI em processo, não subprocesso.** O binding compila
  `piper1-gpl` + `espeak-ng` + `sonic` como *submodules* (fontes, não
  binário pré-compilado) via CMake/NDK, gera `libpiper`, e chama funções
  nativas (`piper_synthesize_start`, `piper_synthesize_next`, etc.) **de
  dentro do mesmo processo do app**, via `piper_jni.cpp`.
- Isso é o pior cenário possível pra um app proprietário: a GPL trata
  "linkar" (estático ou dinâmico, na prática) como formação de "obra
  derivada" — um binário que carrega `libpiper.so` no próprio processo e
  chama funções dele diretamente está, na leitura padrão da FSF, sujeito
  ao copyleft da GPL sobre o conjunto.
- Nenhuma documentação do projeto (README) trata de uso comercial/fechado
  — silêncio total sobre o assunto.

### 2.3 Modelos de voz pt_BR — **não são o problema**

Verificado direto nos `MODEL_CARD` de cada voz (Hugging Face
`rhasspy/piper-voices`):

| Voz | Licença | Origem | Observação |
|---|---|---|---|
| `pt_BR-jeff-medium` | **CC0** | fine-tune do modelo base `rdh` (nl_BE, 1 locutor, CC0) | card não declara gênero |
| `pt_BR-faber-medium` | **CC0** | fine-tune do base `hifi_tts_92` (voz "Cori Samuel") | card não declara gênero |
| `pt_BR-edresson-low` | **CC BY 4.0** | `Edresson/TTS-Portuguese-Corpus`, fine-tune do base inglês "Ryan" | exige atribuição; card não declara gênero |

Confirma o próprio aviso do briefing: **nenhum card declara gênero** — a
tela de laboratório (seção 3) realmente precisa de audição comparativa
antes de rotular qualquer voz como "masculina"/"feminina", porque o nome
("Jeff", "Faber") não é garantia de nada. CC0/CC-BY são licenças
permissivas — **nenhuma das 3 vozes bloqueia uso comercial**; a
`edresson` só precisa de uma linha de atribuição em algum "Sobre"/licenças
do app.

### 2.4 Conclusão da análise

- **Bloqueio real**: o par motor+binding sugerido (`piper1-gpl` via
  `piper-kotlin`) não pode ser embutido como está dentro do processo
  principal do `mplayer` sem herdar obrigações GPL sobre o app inteiro
  (distribuição de código-fonte, mesma licença, etc.) — inviável para um
  produto comercial fechado.
- **Não é bloqueio**: os modelos de voz em si, o ONNX Runtime (Apache
  2.0/MIT, licença permissiva padrão da Microsoft, não pesquisado de novo
  aqui por ser amplamente conhecida) e o conceito de usar Piper como
  motor de síntese.
- **Isto não é parecer jurídico.** É leitura de engenharia sobre o que as
  licenças dizem. Antes da Fase 2 (ver plano abaixo) tocar em qualquer
  linha de `libpiper`, recomendo confirmação de quem cuida de
  compliance/jurídico da Mupa sobre a mitigação da seção 3 — é uma decisão
  de risco de negócio, não só técnica.

---

## 3. Mitigação recomendada: isolar o motor GPL num processo separado

Android permite múltiplos processos **dentro do mesmo APK**, declarando
`android:process=":voz"` num componente (um `Service`, por exemplo) no
`AndroidManifest.xml`. Isso cria um processo Linux de verdade, com seu
próprio espaço de memória — `libpiper.so` carregaria **só** dentro desse
processo `:voz`, nunca no processo principal do `mplayer`. A comunicação
entre os dois processos usa IPC real (AIDL ou `Messenger`), não chamada de
função direta — a mesma fronteira que a FSF trata como "programas
separados" (agregação, não vínculo) na sua própria interpretação pública
da GPL para pipes/sockets/IPC entre processos distintos, em oposição a
linkagem em processo.

Esboço:

```
app principal (mplayer, processo padrão, fechado)
  └── IPC (AIDL: synthesize(texto, vozId, perfil) → bytes PCM / caminho de arquivo)
        └── processo ":voz" (novo módulo, ex. "voice-engine")
              └── libpiper.so (JNI) ← piper1-gpl + espeak-ng (GPL, fontes incluídas no repo)
```

Consequências práticas dessa escolha:
- O módulo `voice-engine` (processo `:voz`) carrega o código GPL — ele
  precisa trazer o código-fonte do `piper1-gpl`/`espeak-ng` usados (ou uma
  oferta por escrito de fornecê-lo), já que o binário final ainda é
  "transmitido" (conveyed) dentro do APK que chega ao cliente. Isso é só
  cumprir a obrigação de disponibilizar fonte da GPL — **não** obriga o
  resto do app (`mplayer` principal) a virar GPL, que é o ponto inteiro do
  isolamento.
- Overhead de IPC (serialização de texto, retorno de áudio) é desprezível
  comparado ao tempo de síntese em si (centenas de ms).
- Crash do motor de voz (modelo corrompido, OOM) derruba só o processo
  `:voz` — o processo principal (reprodução de mídia, consulta de preço)
  continua rodando, reforçando a regra "Fail-Safe Playback" do
  `constitution.md`.
- **Plano B** caso o jurídico não aceite nem o isolamento por processo:
  trocar o motor por uma alternativa com licença permissiva (ex. Kokoro,
  Apache-2.0) — não pesquisado em profundidade ainda, fica como
  alternativa de reserva, não como plano principal (o briefing pediu
  Piper especificamente).

---

## 4. Riscos técnicos a validar antes de aprovar o plano completo

- **Pipeline de build novo pro projeto**: `app/build.gradle.kts` hoje só
  tem `abiFilters` (bibliotecas nativas pré-compiladas existentes) —
  **não existe** `externalNativeBuild`/CMake configurado no `mplayer`
  ainda. Compilar `libpiper` a partir dos submodules (`piper1-gpl` +
  `espeak-ng` + `sonic`) via NDK é uma adição de infraestrutura de build
  real, não uma dependência Gradle trivial.
- **Hardware variado e modesto**: a frota de terminais (ver memória do
  projeto irmão `argos-remote`) inclui aparelhos de baixo desempenho
  (TV boxes genéricas, SoCs variados). Piper é leve o bastante pra rodar
  em Raspberry Pi em tempo real, então a expectativa é boa, mas a seção 9
  do próprio briefing já exige **não considerar aprovado sem teste físico**
  — mantido como gate obrigatório no plano abaixo, não facultativo.
- **Tamanho dos modelos**: modelos "medium" do Piper ficam tipicamente na
  casa de 20–60MB cada (ONNX) — com 2 vozes (jeff + faber) + espeak-ng
  data, isso soma uns 100–150MB a mais no APK/armazenamento do
  dispositivo. Não é bloqueio, mas entra no "Relatório de desempenho por
  dispositivo" pedido na seção 11.

---

## 5. Plano de implementação por etapas

Segue a metodologia já pedida no briefing (seção 12): nada entra no app
de produção antes de validar numa aplicação de teste separada.

### Fase 0 — este documento (concluída)
Arquitetura, análise de licenças, plano. **Aguardando aprovação do
usuário antes da Fase 1.**

### Fase 1 — aplicação de teste independente (novo módulo/projeto, fora do `mplayer` principal)
- Validar `piper1-gpl` compilando via NDK/CMake, sem Kotlin binding
  pronto (ou usando `piper-kotlin` só como referência de como fazer o JNI
  — não necessariamente importando o projeto inteiro como dependência).
- Testar `pt_BR-jeff-medium`, `pt_BR-faber-medium` e `pt_BR-edresson-low`
  isolados: carregar modelo, sintetizar frase fixa, medir tempo.
- Confirmar licenciamento (seção 2) com quem decide compliance na Mupa
  **antes** de avançar pra Fase 2 com o isolamento por processo.

### Fase 2 — laboratório de vozes (seção 3 do briefing)
- Tela de teste (dentro do app de teste da Fase 1, não ainda no
  `mplayer`): seleção de voz/perfil, texto livre, ajuste de
  velocidade/pausas, áudio comparativo A/B, exportar `.wav` de teste.
- Esse é o momento de decidir de fato qual voz "soa" mais masculina/
  feminina/comercial — não presumir pelo nome (seção 2 do briefing,
  confirmado pela análise de licenças acima: os cards nem declaram
  gênero).

### Fase 3 — `MupaVoiceEngine` + `SpeechTextFormatter` de adaptação
- Módulo Kotlin novo (`voice-engine`, processo isolado `:voz` — seção 3
  deste doc) com a API pública descrita na seção 6 do briefing
  (inicialização única, fila, cancelamento, cache, PCM via `AudioTrack`).
- **Adaptador**, não reescrita: uma função fina que recebe o texto que
  `OfferIntelligence`/`speakSlotsIfPossible` já produzem e repassa pro
  motor novo — reaproveita 100% da lógica monetária/promocional existente
  (seção 1.1 deste doc).
- Cache local por `(texto normalizado, voiceId, perfil, parâmetros,
  versão do modelo)` — mesmo padrão de hash SHA-256 que `PriceVoiceSynth`
  já usa hoje, adaptado pra incluir perfil de locução.

### Fase 4 — integração com consulta de preços, sem quebrar o fluxo atual
- Substitui o papel de `PriceVoiceSynth` em `speakWithVoiceSynth`
  (`PlayerActivity.kt:4187`), preservando `speakNative` como único
  fallback final.
- Configurações administrativas (seção 7): habilitar/desabilitar áudio,
  selecionar voz/perfil, por trás do mesmo `SettingsManager`/tela de
  Configurações que já existe.

### Fase 5 — desempenho e relatório por dispositivo (seção 9, gate obrigatório)
- Testes em pelo menos um terminal físico real de cada perfil de hardware
  relevante (não só emulador) — tempo de inicialização, tempo até 1º
  áudio, RAM, CPU, qualidade percebida.
- **Critério de aprovação**: só considerar a Fase 4 pronta pra produção
  depois desse relatório, conforme já exigido no próprio briefing.

### Fase 6 (futuro, não neste ciclo) — treinamento de vozes proprietárias (seção 10)
- Infraestrutura de dataset/treinamento VITS/fine-tuning fica fora do
  terminal Android, como o próprio briefing já especifica — não há
  trabalho de app aqui ainda, só uma nota de que isso vem depois da Fase 5
  validada.

---

## 6. O que precisa de decisão humana antes de eu escrever qualquer código

1. **Aprovar (ou rejeitar) a mitigação de isolamento por processo** da
   seção 3 — com o entendimento de que isso ainda pede confirmação de
   compliance/jurídico, não é uma garantia de segurança total.
2. Se rejeitada: decidir entre (a) abrir mão do Piper e usar um motor com
   licença permissiva, ou (b) negociar/pagar por uma licença comercial
   separada do Piper/espeak-ng caso os mantenedores ofereçam uma (não
   pesquisado se existe).
3. ~~Confirmar que a Fase 1 pode começar~~ — aprovado pelo usuário em
   2026-11-17, Fase 1 executada e concluída (ver seção 7 abaixo).

## 7. Fase 1 — resultado (concluída, 2026-11-17)

Projeto de teste separado criado fora do `mplayer` principal, em
`C:\Users\rdpadmin\src\mupa-locutor-test-ref` (clone local de
`piper-tts-apps/piper-kotlin`, não publicado/commitado em lugar nenhum —
é só o ambiente de validação da Fase 1, consistente com a seção 12 do
briefing: nada entra no app de produção ainda).

### O que foi validado

- **Compilação real do `piper1-gpl` via NDK/CMake**, as 3 ABIs relevantes
  (arm64-v8a, armeabi-v7a, x86_64) — `libpiper_jni.so` + `libonnxruntime.so`
  gerados com sucesso.
- **As 3 vozes pedidas** (`pt_BR-jeff-medium`, `pt_BR-faber-medium`,
  `pt_BR-edresson-low`) sintetizando corretamente, com o dicionário
  `pt`/`pt-br` do espeak-ng compilado (o repo de referência só vinha com
  o dicionário inglês pronto).
- **Testado em HARDWARE FÍSICO REAL**: o próprio K11 (rk3576, Android 14)
  citado em `PlayerActivity.kt` do mplayer como aparelho sem motor de TTS
  nativo nenhum — exatamente o perfil de dispositivo mais crítico pra essa
  feature.

### Métricas de desempenho (frases reais de preço, não texto genérico)

| Voz | Sample rate | Init (1ª vez) | TTFA* preço simples | TTFA promoção (4 frases) | Tempo total promoção | RMS** |
|---|---|---|---|---|---|---|
| jeff-medium | 22050 Hz | ~4,0s | ~770-840ms | ~320ms | ~2,5s pra 9,1s de áudio | 0,075 |
| faber-medium | 22050 Hz | ~3,9s | ~770-790ms | ~370ms | ~2,6s pra 9,0s de áudio | 0,075-0,085 |
| edresson-low | 16000 Hz | ~6,0s | ~510-590ms | ~180ms | ~1,7s pra 7,8s de áudio | **0,018-0,027 (bem mais baixo)** |

\* TTFA = tempo até o 1º chunk de áudio (o que o operador realmente
percebe como "demora pra começar a falar").
\*\* RMS = proxy de volume/energia do áudio — não é qualidade, só
amplitude. `edresson` sai perceptivelmente mais baixo que as outras duas;
se for a voz escolhida, vai precisar de normalização de ganho.

**Leitura prática**: tempo até o 1º áudio fica sempre abaixo de 1
segundo pra uma frase de preço normal, mesmo no aparelho mais fraco da
frota — dentro do aceitável pra UX de terminal de consulta. O custo de
~4-6s de inicialização é só na primeira vez (criar o engine); a regra do
próprio briefing ("modelo carregado uma única vez e reutilizado") já
assume isso.

### Áudio gerado (ouvir antes da Fase 2)

9 arquivos `.wav` (3 vozes × 3 frases: preço simples, preço por quilo,
promoção) gerados e enviados pro usuário — ponto de partida real pro
Laboratório de Vozes da Fase 2, já que nenhum `MODEL_CARD` declara gênero
e os nomes ("Jeff", "Faber") não são garantia de nada.

### Achados de engenharia não previstos no briefing original

1. **`piper-kotlin` nunca foi testado no Windows** — `build.gradle.kts`
   interpola `${rootDir}` (barra invertida nativa) direto num argumento
   `-D` do CMake; `C:\Users\...` contém a sequência `\U` (de `\Users`),
   que o parser do CMake interpreta como escape Unicode inválido
   ("Invalid character escape '\U'"), quebrando a configuração pra
   QUALQUER ABI. Corrigido no clone local normalizando pra barra normal
   antes de montar os argumentos — se algum dia vencer a decisão de usar
   essa lib de verdade no `mplayer`, esse fix precisa ir junto (ou
   reportado upstream).
2. **Esta máquina de desenvolvimento não tinha toolchain C/C++ nenhum**
   (nem MSVC/Windows SDK, nem MinGW) — só o NDK (que só compila PRA
   Android, não pro host). Instalado LLVM-MinGW via winget
   especificamente pra poder compilar o `espeak-ng` localmente (precisa
   rodar no PC pra gerar os dados de fonemização que depois vão pro
   device) — LLVM puro falhou por depender de libs do Windows SDK
   ausentes (`kernel32.lib` etc.).
3. **git-bash (MSYS) converte automaticamente argumentos com cara de path
   Unix pra path Windows** — isso mascarou/causou 3 falhas diferentes
   nesta sessão (um `adb shell mkdir` remoto virando `mkdir 'C:'`, um
   `adb push` pro destino errado, e um argumento `-P` do Gradile
   (`espeakDataPath`) sendo reescrito pra um caminho Windows local antes
   de chegar no dispositivo). Fix usado: prefixar esses argumentos
   específicos com barra dupla (`//data/local/tmp/...`) — `MSYS_NO_PATHCONV=1`
   global quebra o próprio script `gradlew` (ele depende da conversão
   pra achar o `gradle-wrapper.jar`).
4. **AGP desinstala o APK de teste automaticamente ao final do
   `connectedAndroidTest`**, levando junto `/sdcard/Android/data/<pkg>`
   (onde os `.wav` de validação foram escritos) — o flag
   `-Pandroid.injected.androidTest.leaveApksInstalled=true` não impediu
   isso nesta versão do AGP. Contornado instalando e rodando a
   instrumentação manualmente via `adb install` + `adb shell am
   instrument`, puxando os arquivos antes de desinstalar na mão.

### Pendente antes da Fase 2 de verdade

- Confirmação de compliance/jurídico sobre a mitigação de isolamento por
  processo (seção 3) — Fase 1 não precisou disso (ambiente de teste
  isolado, não distribuído a cliente nenhum), mas a Fase 3/4 (integração
  no `mplayer`) precisa.
- Ouvir os 9 `.wav` e decidir, de fato, qual voz(es) levar adiante.

---

## 8. Reação do usuário: Piper não soou bem — pivô pra pesquisar alternativas (2026-11-17)

Depois de ouvir os 9 `.wav` da Fase 1, feedback do usuário: **"não ficou
bom"** — esperava algo mais perto da qualidade de uma voz de referência
do ElevenLabs (link de Voice Lab compartilhado, exige login — não
acessado, é conta pessoal/comercial do usuário, nunca vou entrar com
credenciais de terceiros).

**Ponto de fundo, não só de voz específica**: ElevenLabs roda modelos
gigantes na nuvem; qualquer TTS 100% offline que rode num terminal fraco
(K11 rk3576) usa modelos de ~60-300MB pra caber em tempo real num
hardware fraco. Essa é uma diferença de categoria de tecnologia, não algo
que se resolve trocando de voz ou ajustando parâmetro.

### 8.1 Pesquisa de alternativas ao Piper — nenhuma resolve os dois problemas ao mesmo tempo

| Opção | Licença | pt-BR? | Problema real |
|---|---|---|---|
| **Piper** (Fase 1) | GPL-3.0 | sim, 3 vozes | GPL + qualidade que o usuário já rejeitou |
| **Kokoro-82M** | Apache-2.0 (limpa pro modelo) | sim — `pf_dora`(F)/`pm_alex`(M)/`pm_santa`(M), lang_code `'p'` | fonemização em pt usa **espeak-ng (GPL-3.0)** — mesmo problema do Piper, só que um passo adiante. Autores avisam que vozes não-inglesas podem ser "thin"; nenhuma nota de qualidade pras 3 vozes pt-br |
| **MeloTTS** | MIT (limpa) | **não existe** | precisaria treinar do zero |
| **XTTS-v2 (Coqui)** | **Coqui Public Model License** | sim, 17 idiomas | **proíbe uso comercial/que gere receita explicitamente** ("not a non-commercial purpose" até pra atividade financiada por governo) — descartado, não é negociável sem licença comercial separada (incerto se a Coqui como empresa ainda vende isso) |
| **gruut** (fonemizador, não TTS completo) | MIT, **sem espeak-ng** nas dependências | pt existe (`gruut-lang-pt`) mas "thin"/não documentado | projeto **arquivado** (owner arquivou em 2025-10-06); sozinho não gera áudio, precisaria casar com motor acústico — engenharia não trivial, não testada por ninguém |

Fontes: model cards/READMEs/LICENSE de cada projeto no GitHub/HuggingFace,
pesquisados ao vivo em 2026-11-17 (ver citações completas no histórico da
sessão que gerou este documento).

### 8.2 Decisão do usuário: testar as 3 vozes do Kokoro mesmo assim

Mesmo sabendo que o espeak-ng (GPL) ainda está no caminho do pt-BR do
Kokoro — a mesma mitigação de isolamento por processo da seção 3 serviria
igual —, o usuário pediu pra ouvir as 3 vozes antes de descartar.

### 8.3 Caminho de teste MUITO mais rápido que o do Piper: APK pronto da sherpa-onnx

Diferente da Fase 1 (que exigiu clonar+compilar `piper1-gpl` via NDK/CMake
do zero), achei que **k2-fsa/sherpa-onnx** (Apache-2.0, wrapper de
produção pra várias engines TTS incluindo Kokoro, com suporte Android já
pronto) publica **APKs pré-compilados** com o modelo Kokoro embutido —
zero compilação necessária:

- Índice: https://k2-fsa.github.io/sherpa/onnx/tts/apk.html
- APK usado (arm64, 357MB, já embute `kokoro-multi-lang-v1_0`):
  `https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-new/1.12.21/sherpa-onnx-1.12.21-arm64-v8a-zh_en-tts-kokoro-multi-lang-v1_0.apk`
- Mapa de speaker ID confirmado ao vivo via logcat na inicialização:
  `pf_dora=42, pm_alex=43, pm_santa=44` (de 53 vozes totais do bundle
  multilíngue).
- **Ressalva real dos próprios mantenedores**: a doc diz que esse pacote
  "só adicionou suporte a inglês e chinês" — ou seja, mesmo com as 3 vozes
  pt-br existindo no `voices.bin`, não havia garantia de que o pipeline de
  texto (frontend) do pt-BR estivesse funcionando corretamente nessa
  empacotação específica. Confirmado ao vivo que carrega e sintetiza sem
  erro (ver 8.5) — não valida pronúncia fina, só que não quebra.

**Achado de processo, não descartado**: instalar esse APK pré-compilado
de terceiro (357MB, baixado do Hugging Face, binário que não dá pra
auditar) foi **bloqueado pelo classificador de modo automático do Claude
Code** (categoria "Undermining Oversight") na primeira tentativa — correto,
é exatamente o tipo de ação que não devo fazer sozinho. Só prossegui
depois que o usuário autorizou explicitamente essa instalação específica
("instala no K11 e testa").

### 8.4 Automação cega via `adb` — achados de ferramenta, guardar pra próxima sessão

Sem mirroring de tela (só shell), a interação com o app foi 100% às cegas
via `uiautomator dump` (acessibility tree) + `input tap`/`input text`.
Achados reais, não específicos deste projeto:

1. **`uiautomator dump` falha com "null root node"** nos primeiros ~2-10s
   depois de um `am start` — o app ainda está inicializando (aqui,
   carregando o modelo Kokoro de ~310MB, que levou ~10s pra terminar,
   confirmado via logcat `"Displayed ... +10s559ms"`). Sempre esperar o
   app assentar antes do primeiro dump, não confiar num dump que retornou
   vazio/erro como "app não abriu".
2. **`adb shell input text` com caractere acentuado CRASHA** nesse
   firmware específico (`java.lang.NullPointerException: Attempt to get
   length of null array` em `InputShellCommand.sendText`) — bug conhecido
   de builds AOSP com fallback de Unicode incompleto pra `input text`, não
   é limitação do Kokoro/espeak-ng. Mitigação usada: digitar as frases de
   teste SEM acento pra esse teste exploratório específico (ex. "Tres" em
   vez de "Três") — funciona pra ouvir o timbre da voz, mas não valida a
   pronúncia fina de palavras acentuadas. Se precisar de acento de
   verdade via automação cega no futuro: instalar o app "ADB Keyboard"
   (IME que aceita texto via broadcast, bypassa esse bug) — não feito
   aqui por ser mais uma instalação de app de terceiro a autorizar.
3. **Teclado virtual cobre os botões de baixo** sempre que um EditText
   ganha foco — depois de preencher um campo, sempre fechar o teclado
   (`input keyevent 4`, KEYCODE_BACK) e tirar um dump NOVO antes de confiar
   em qualquer bounds de botão capturado antes do campo ser preenchido
   (um dump "estale" mostrava botões em posições que, com o teclado
   aberto, na real estavam cobertos — um tap cego nessas coordenadas
   acertou uma tecla do teclado em vez do botão, inserindo texto lixo no
   campo).
4. **CUIDADO**: KEYCODE_BACK nem sempre só fecha o teclado — numa
   tentativa, com o teclado já fechado por outro motivo, o BACK saiu do
   app inteiro de volta pro launcher. Sempre conferir `package=` no dump
   seguinte pra confirmar que ainda está dentro do app esperado antes de
   continuar a sequência.
5. **Esse APK específico (variante "tts-new", não "tts-engine") não tem
   botão save/share** — só Generate/Play/Stop. O áudio gerado vai pra
   `filesDir` **privado** do app (`/data/data/<pkg>/files/generated.wav`),
   não pra storage externo.
6. **App não é debuggable** (`run-as` falha: "package not debuggable") —
   mas **`adb root` funcionou nesse K11 especificamente** (dispositivo de
   teste/desenvolvimento, não um device travado), permitindo `adb shell
   cat /data/data/<pkg>/files/generated.wav > local.wav` direto, sem
   precisar do fluxo de save/share pela UI. Isso só funciona porque este
   K11 em particular aceita `adb root` — não necessariamente true pra
   outros aparelhos da frota (muitos são produção real, sem esse acesso).

### 8.5 Resultado parcial (pausado pelo usuário, retomar depois)

**1 de 9 áudios gerado e confirmado válido** (RIFF/WAVE, 24kHz mono
16-bit, 133KB): `pf_dora` (speaker id 42) + "Sete reais e noventa e nove
centavos." — carregou e sintetizou sem erro, sem crash, pt-br processado
(apesar da ressalva da seção 8.3 sobre "só inglês/chinês" oficialmente
suportado nesse bundle). **Áudio ainda não foi ouvido pelo usuário.**

Trabalho pausado a pedido do usuário (precisava do K11 pra outro teste)
no meio da preparação da 2ª frase (`pf_dora` + "Tres reais e cinquenta
centavos por quilo.", sem acento) — campos já preenchidos corretamente,
teclado fechado, botão Generate confirmado visível e clicável, mas
**NENHUM tap em Generate foi dado** depois disso. Nada mais foi tocado no
device depois do pedido de pausa.

**Pra retomar** (nesta ordem, reaproveitando o ambiente já preparado):
1. Confirmar que o app `com.k2fsa.sherpa.onnx` ainda está instalado e em
   primeiro plano (`adb shell dumpsys window | grep mCurrentFocus` ou
   simplesmente um `uiautomator dump` e checar `package=`).
2. Repetir o padrão da seção 8.4 pra gerar as 8 combinações restantes:
   `pf_dora`(42)+preco_kg (campos já setados, só falta tocar Generate),
   `pf_dora`+promocao, `pm_alex`(43) × 3 frases, `pm_santa`(44) × 3 frases.
3. Puxar cada `.wav` via `adb shell cat .../generated.wav > arquivo.wav`
   (precisa `adb root` de novo se a sessão adb caiu) logo depois de cada
   geração, antes de sobrescrever gerando a próxima.
4. Enviar os 9 (ou quantos sobrarem) pro usuário ouvir.
5. Decisão do usuário: alguma das 3 vozes do Kokoro chega perto do nível
   desejado (ElevenLabs)? Se não, próxima decisão é entre usar nuvem
   (ElevenLabs via a mesma arquitetura já validada em produção,
   `PriceVoiceSynth`/cache) ou encerrar a frente offline por ora.

Arquivos desta investigação ficaram em
`C:\Users\rdpadmin\src\mupa-locutor-test-ref\kokoro-apk\` (fora do
`mplayer`, mesmo padrão de ambiente de teste isolado da Fase 1): o APK
baixado, o `.wav` já obtido, e os dumps de UI intermediários (podem ser
descartados, só documentação de processo).
