import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

const SECRET_SEED = "mupa_player_enterprise_secure_seed_2026";

async function decryptAES(cipherTextBase64: string, seed: string): Promise<string> {
  try {
    const encoder = new TextEncoder();
    const seedBytes = encoder.encode(seed);
    const keyHash = await crypto.subtle.digest("SHA-256", seedBytes);
    
    const key = await crypto.subtle.importKey(
      "raw",
      keyHash,
      { name: "AES-CBC" },
      false,
      ["decrypt"]
    );
    
    const iv = new Uint8Array(keyHash).slice(0, 16);
    const encryptedBytes = Uint8Array.from(atob(cipherTextBase64), c => c.charCodeAt(0));
    
    const decryptedBytes = await crypto.subtle.decrypt(
      { name: "AES-CBC", iv },
      key,
      encryptedBytes
    );
    
    return new TextDecoder().decode(decryptedBytes);
  } catch (e) {
    console.error("Decryption failed:", e);
    return cipherTextBase64;
  }
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: corsHeaders })
  }

  try {
    const supabaseClient = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    const payload = await req.json()
    const encryptedDevice = payload.device
    if (!encryptedDevice) {
      return new Response(JSON.stringify({ error: 'Device is required' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 400,
      })
    }

    const deviceId = await decryptAES(encryptedDevice, SECRET_SEED)
    console.log(`[Audience-Analytics] Decrypted device ID: ${deviceId}`)

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    const isUuid = uuidRegex.test(deviceId)

    let dbQuery = supabaseClient
      .from('dispositivos')
      .select('id, serial, company_id, tenant_id')

    if (isUuid) {
      dbQuery = dbQuery.or(`device_uuid.eq."${deviceId}",serial.eq."${deviceId}"`)
    } else {
      dbQuery = dbQuery.eq('serial', deviceId)
    }

    const { data: device, error: deviceError } = await dbQuery.maybeSingle()

    if (deviceError || !device) {
      console.error('Device not found for:', deviceId, deviceError)
      return new Response(JSON.stringify({ error: 'Device not found', details: deviceError?.message }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 404,
      })
    }

    const sessions = payload.sessions || []
    console.log(`[Audience-Analytics] Processing ${sessions.length} sessions for device ${device.serial}`)

    const inserts = sessions.map((s: any) => {
      return {
        session_id: s.id,
        device_id: device.serial,
        company_id: device.company_id,
        tenant_id: device.tenant_id,
        face_hash: null, // Overwritten to null for privacy
        detected_at: new Date(s.first_seen).toISOString(),
        gender: s.gender,
        gender_probability: s.confidence,
        attention_seconds: s.view_duration_seconds,
        screen_time: s.view_duration_seconds,
        metadata: {
          age_range: s.age_range,
          hour: s.hour,
          weekday: s.weekday,
          content_playing: s.content_playing,
          playlist: s.playlist,
          look_count: s.look_count,
          last_seen: s.last_seen
        }
      }
    })

    if (inserts.length > 0) {
      const { error: insertError } = await supabaseClient
        .from('audience_detections')
        .insert(inserts)

      if (insertError) {
        console.error('Error inserting audience detections:', insertError)
        return new Response(JSON.stringify({ error: insertError.message }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          status: 500,
        })
      }
    }

    return new Response(JSON.stringify({ success: true }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 200,
    })
  } catch (error) {
    console.error('Unexpected error:', error)
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 500,
    })
  }
})
