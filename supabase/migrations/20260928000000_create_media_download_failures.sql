-- 1. Create media_download_failures table with tenant_id column
CREATE TABLE IF NOT EXISTS public.media_download_failures (
    id UUID PRIMARY KEY,
    device_id TEXT NOT NULL,
    tenant_id UUID REFERENCES public.tenants(id) ON DELETE SET NULL,
    media_id TEXT NOT NULL,
    media_name TEXT,
    url TEXT NOT NULL,
    error_reason TEXT NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- 2. Enable RLS
ALTER TABLE public.media_download_failures ENABLE ROW LEVEL SECURITY;

-- 3. RLS Policies
DROP POLICY IF EXISTS "Allow inserts for anon and authenticated" ON public.media_download_failures;
CREATE POLICY "Allow inserts for anon and authenticated" 
ON public.media_download_failures FOR INSERT TO anon, authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "Allow select for authenticated" ON public.media_download_failures;
CREATE POLICY "Allow select for authenticated"
ON public.media_download_failures FOR SELECT TO authenticated USING (true);

-- 4. Create trigger function to auto-fill tenant_id from public.dispositivos using serial (device_id)
CREATE OR REPLACE FUNCTION public.fill_media_download_failures_tenant_id()
RETURNS TRIGGER AS $$
BEGIN
    SELECT tenant_id INTO NEW.tenant_id
    FROM public.dispositivos
    WHERE serial = NEW.device_id
    LIMIT 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 5. Bind trigger to media_download_failures table
DROP TRIGGER IF EXISTS tr_fill_media_download_failures_tenant_id ON public.media_download_failures;
CREATE TRIGGER tr_fill_media_download_failures_tenant_id
    BEFORE INSERT ON public.media_download_failures
    FOR EACH ROW
    EXECUTE FUNCTION public.fill_media_download_failures_tenant_id();
