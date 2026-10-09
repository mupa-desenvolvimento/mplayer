import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"
import { S3Client, PutObjectCommand } from "npm:@aws-sdk/client-s3@3.1142.0"

// TTS por voz (Azure Cognitive Services, "TTS do Bing" — ver
// docs/Request_TTS_BING.txt) com cache compartilhado por toda a frota.
// Pedido do usuário (2026-10-09): "não quero gastar, reaproveitando os
// preços que ela vai dizer". A chave da Azure e as credenciais do R2 só
// existem AQUI (secrets da Edge Function via `supabase secrets set`),
// nunca no APK — diferente do EngageTts.kt (engage-tts), que ainda chama
// a Azure direto do device pra outra tela (engajamento), com a key
// hardcoded no código-fonte (achado conhecido, fora de escopo corrigir
// agora).
//
// Fluxo: texto+voz -> hash -> já existe linha na tabela? devolve a
// public_url de cara (e conta o uso). Senão: pega token da Azure, gera o
// áudio, sobe pro R2, grava a linha, devolve a public_url nova. Qualquer
// outro device que pedir o MESMO texto (ex.: a mesma frase de oferta em
// outra loja) cai direto no primeiro caminho — zero chamada nova à Azure.
//
// O app Android só chama esta function quando NEM o cache local (arquivo
// em disco) NEM uma leitura direta de `tts_audio_cache` via REST (mais
// barata que invocar a function) acharam o áudio — ver
// PriceVoiceSynth.kt no app.

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

const AZURE_REGION = Deno.env.get('AZURE_TTS_REGION') ?? 'brazilsouth'
const AZURE_SUBSCRIPTION_KEY = Deno.env.get('AZURE_TTS_SUBSCRIPTION_KEY') ?? ''
const DEFAULT_VOICE = 'pt-BR-FranciscaNeural'
const MAX_TEXT_LENGTH = 500

const R2_ENDPOINT = Deno.env.get('R2_ENDPOINT') ?? ''
const R2_ACCESS_KEY_ID = Deno.env.get('R2_ACCESS_KEY_ID') ?? ''
const R2_SECRET_ACCESS_KEY = Deno.env.get('R2_SECRET_ACCESS_KEY') ?? ''
const R2_BUCKET = Deno.env.get('R2_BUCKET') ?? ''
const R2_PUBLIC_BASE_URL = (Deno.env.get('R2_PUBLIC_BASE_URL') ?? '').replace(/\/$/, '')

const r2Client = new S3Client({
  region: 'auto',
  endpoint: R2_ENDPOINT,
  credentials: { accessKeyId: R2_ACCESS_KEY_ID, secretAccessKey: R2_SECRET_ACCESS_KEY },
  // Sem isto, PUT pro R2 com SDK novo pode voltar 403 SignatureDoesNotMatch
  // — mesmo achado real já documentado no gateway do argos-remote.
  requestChecksumCalculation: 'WHEN_REQUIRED',
})

// Cache do token da Azure em memória do processo (isolate pode ficar
// "morno" entre invocações no Deno Deploy) — mesmo princípio do
// EngageTts.kt (cache de 9min, token real dura 10min), evita uma chamada
// de STS extra por síntese quando a function é invocada em sequência.
let cachedAzureToken: { token: string; expiresAtMs: number } | null = null

async function getAzureToken(): Promise<string> {
  const now = Date.now()
  if (cachedAzureToken && now < cachedAzureToken.expiresAtMs) return cachedAzureToken.token
  const resp = await fetch(`https://${AZURE_REGION}.api.cognitive.microsoft.com/sts/v1.0/issuetoken`, {
    method: 'POST',
    headers: { 'Ocp-Apim-Subscription-Key': AZURE_SUBSCRIPTION_KEY, 'Content-Length': '0' },
  })
  if (!resp.ok) throw new Error(`azure_token_http_${resp.status}`)
  const token = (await resp.text()).trim()
  if (!token) throw new Error('azure_token_empty')
  cachedAzureToken = { token, expiresAtMs: now + 9 * 60 * 1000 }
  return token
}

function buildSsml(text: string, voiceName: string): string {
  const escaped = text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;')
  return `<speak version='1.0' xml:lang='pt-BR'><voice xml:lang='pt-BR' xml:gender='Female' name='${voiceName}'>${escaped}</voice></speak>`
}

async function synthesizeAzure(text: string, voiceName: string): Promise<Uint8Array> {
  const token = await getAzureToken()
  const resp = await fetch(`https://${AZURE_REGION}.tts.speech.microsoft.com/cognitiveservices/v1`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/ssml+xml',
      // MP3 — mesmo formato já usado/testado em EngageTts.kt (MediaPlayer
      // no Android toca nativamente, sem decodificar PCM na mão).
      'X-Microsoft-OutputFormat': 'audio-24khz-48kbitrate-mono-mp3',
      'User-Agent': 'MupaPlayer-TtsSynthesize/1.0',
    },
    body: buildSsml(text, voiceName),
  })
  if (!resp.ok) {
    const errBody = await resp.text().catch(() => '')
    throw new Error(`azure_synthesis_http_${resp.status}: ${errBody.slice(0, 200)}`)
  }
  return new Uint8Array(await resp.arrayBuffer())
}

async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('')
}

serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { headers: corsHeaders })

  try {
    if (!AZURE_SUBSCRIPTION_KEY || !R2_ENDPOINT || !R2_ACCESS_KEY_ID || !R2_SECRET_ACCESS_KEY || !R2_BUCKET || !R2_PUBLIC_BASE_URL) {
      return new Response(JSON.stringify({ error: 'tts_not_configured' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 501,
      })
    }

    const payload = await req.json().catch(() => ({}))
    const text = String(payload.text ?? '').trim()
    const voiceName = String(payload.voiceName ?? DEFAULT_VOICE).trim() || DEFAULT_VOICE
    if (!text) {
      return new Response(JSON.stringify({ error: 'missing_text' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 400,
      })
    }
    if (text.length > MAX_TEXT_LENGTH) {
      return new Response(JSON.stringify({ error: 'text_too_long' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 400,
      })
    }

    const textHash = await sha256Hex(`${voiceName}|${text}`)

    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '',
    )

    const { data: existing } = await supabase
      .from('tts_audio_cache')
      .select('public_url, use_count')
      .eq('text_hash', textHash)
      .maybeSingle()

    if (existing) {
      // Contagem de uso é só telemetria (não bloqueia a resposta se falhar).
      supabase
        .from('tts_audio_cache')
        .update({ use_count: (existing.use_count ?? 1) + 1, last_used_at: new Date().toISOString() })
        .eq('text_hash', textHash)
        .then(() => {}, () => {})
      return new Response(JSON.stringify({ publicUrl: existing.public_url, cached: true }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 200,
      })
    }

    const audioBytes = await synthesizeAzure(text, voiceName)
    if (!audioBytes.length) throw new Error('azure_synthesis_empty')

    const r2Key = `tts-cache/${textHash}.mp3`
    await r2Client.send(new PutObjectCommand({
      Bucket: R2_BUCKET, Key: r2Key, Body: audioBytes, ContentType: 'audio/mpeg',
    }))
    const publicUrl = `${R2_PUBLIC_BASE_URL}/${r2Key}`

    // upsert (não insert simples): duas síntese simultâneas da mesma frase
    // em devices diferentes não devem colidir em erro de PK duplicada —
    // a segunda só sobrescreve com os mesmos dados (texto/voz são a
    // chave, o resultado é determinístico).
    const { error: insertError } = await supabase
      .from('tts_audio_cache')
      .upsert(
        { text_hash: textHash, voice_name: voiceName, text, r2_key: r2Key, public_url: publicUrl },
        { onConflict: 'text_hash' },
      )
    if (insertError) console.error('[tts-synthesize] upsert_failed', insertError)

    return new Response(JSON.stringify({ publicUrl, cached: false }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 200,
    })
  } catch (error) {
    console.error('[tts-synthesize] error', error)
    return new Response(JSON.stringify({ error: (error as Error).message ?? 'unknown_error' }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 500,
    })
  }
})
