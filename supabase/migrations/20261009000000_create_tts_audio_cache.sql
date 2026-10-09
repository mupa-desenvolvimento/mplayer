-- Cache compartilhado (toda a frota) de áudio TTS já sintetizado pela Azure
-- e armazenado no R2 — pedido do usuário (2026-10-09): "não quero gastar,
-- conseguimos criar uma forma de salvar os preços que vai gerar,
-- reaproveitando os preços que ela vai dizer". Chave é o hash do texto+voz,
-- não o EAN/preço em si — qualquer frase idêntica ("De R$ 9,88. por, R$
-- 7,59.") dita por QUALQUER loja da frota reusa o mesmo arquivo.
--
-- Sem company_id/tenant_id de propósito: o áudio não contém nenhum dado
-- específico de empresa, só o texto falado, então o cache é global.
CREATE TABLE IF NOT EXISTS public.tts_audio_cache (
    text_hash TEXT PRIMARY KEY,
    voice_name TEXT NOT NULL,
    text TEXT NOT NULL,
    r2_key TEXT NOT NULL,
    public_url TEXT NOT NULL,
    use_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL,
    last_used_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL
);

ALTER TABLE public.tts_audio_cache ENABLE ROW LEVEL SECURITY;

-- Só LEITURA pros dispositivos (mesmo token "authenticated" usado em
-- price_query_events/media_download_failures) — permite checar o cache
-- direto via REST sem gastar uma invocação da Edge Function. ESCRITA
-- (INSERT/UPDATE) não tem policy nenhuma: só a Edge Function
-- tts-synthesize (service_role, que ignora RLS) gera/atualiza linhas —
-- nenhum dispositivo sintetiza nem grava cache por conta própria.
DROP POLICY IF EXISTS "Allow select for authenticated" ON public.tts_audio_cache;
CREATE POLICY "Allow select for authenticated"
ON public.tts_audio_cache FOR SELECT TO authenticated USING (true);

CREATE INDEX IF NOT EXISTS idx_tts_audio_cache_last_used_at ON public.tts_audio_cache (last_used_at);
