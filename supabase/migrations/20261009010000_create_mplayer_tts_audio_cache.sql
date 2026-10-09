-- Cache compartilhado (toda a frota de MPlayers) de áudio TTS já sintetizado
-- pela Azure e armazenado no R2 — pedido do usuário (2026-10-09): "não
-- quero gastar, conseguimos criar uma forma de salvar os preços que vai
-- gerar, reaproveitando os preços que ela vai dizer". Chave é o hash do
-- texto+voz, não o EAN/preço em si — qualquer frase idêntica dita por
-- QUALQUER loja da frota reusa o mesmo arquivo.
--
-- Nome PREFIXADO com "mplayer_" de propósito (achado real, 2026-10-09):
-- este banco Supabase é compartilhado por vários produtos da empresa, não
-- só o mplayer. Já existia uma tabela "tts_audio_cache" (schema
-- diferente: text_content/voice_id/audio_url) alimentada pela function
-- "elevenlabs-tts", provavelmente do produto Content TV, em uso desde
-- março/2026. Esta tabela aqui é exclusiva desta feature, sem qualquer
-- relação com aquela.
--
-- Sem company_id/tenant_id de propósito: o áudio não contém nenhum dado
-- específico de empresa, só o texto falado, então o cache é global.
CREATE TABLE IF NOT EXISTS public.mplayer_tts_audio_cache (
    text_hash TEXT PRIMARY KEY,
    voice_name TEXT NOT NULL,
    text TEXT NOT NULL,
    r2_key TEXT NOT NULL,
    public_url TEXT NOT NULL,
    use_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL,
    last_used_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL
);

ALTER TABLE public.mplayer_tts_audio_cache ENABLE ROW LEVEL SECURITY;

-- Só LEITURA pros dispositivos — permite checar o cache direto via REST
-- sem gastar uma invocação da Edge Function. ESCRITA (INSERT/UPDATE) não
-- tem policy nenhuma: só a Edge Function tts-synthesize (service_role,
-- que ignora RLS) gera/atualiza linhas — nenhum dispositivo sintetiza
-- nem grava cache por conta própria.
--
-- Achado real (2026-10-09): o app usa a ANON key como "SUPABASE_TOKEN"
-- (confirmado decodificando o JWT — role:"anon", não "authenticated"),
-- diferente do que o padrão copiado de price_query_events/
-- media_download_failures (SELECT só pra "authenticated") sugeria — lá
-- não importa porque o device só faz INSERT nessas tabelas (que inclui
-- anon na policy), nunca SELECT. Aqui o SELECT é o ponto principal do
-- cache, então precisa incluir anon de verdade, testado ao vivo.
DROP POLICY IF EXISTS "Allow select for authenticated" ON public.mplayer_tts_audio_cache;
DROP POLICY IF EXISTS "Allow select for anon and authenticated" ON public.mplayer_tts_audio_cache;
CREATE POLICY "Allow select for anon and authenticated"
ON public.mplayer_tts_audio_cache FOR SELECT TO anon, authenticated USING (true);

CREATE INDEX IF NOT EXISTS idx_mplayer_tts_audio_cache_last_used_at ON public.mplayer_tts_audio_cache (last_used_at);
