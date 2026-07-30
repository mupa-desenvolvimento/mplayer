-- 1. Create price_query_events table with tenant_id column
CREATE TABLE IF NOT EXISTS public.price_query_events (
    id UUID PRIMARY KEY,
    device_id TEXT NOT NULL,
    tenant_id UUID REFERENCES public.tenants(id) ON DELETE SET NULL,
    filial TEXT,
    ean TEXT NOT NULL,
    descricao TEXT,
    created_at_epoch_ms BIGINT NOT NULL,
    response_time_ms BIGINT,
    from_cache BOOLEAN,
    success BOOLEAN,
    created_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- 2. Enable RLS
ALTER TABLE public.price_query_events ENABLE ROW LEVEL SECURITY;

-- 3. RLS Policies
DROP POLICY IF EXISTS "Allow inserts for anon and authenticated" ON public.price_query_events;
CREATE POLICY "Allow inserts for anon and authenticated" 
ON public.price_query_events FOR INSERT TO anon, authenticated WITH CHECK (true);

DROP POLICY IF EXISTS "Allow select for authenticated" ON public.price_query_events;
CREATE POLICY "Allow select for authenticated"
ON public.price_query_events FOR SELECT TO authenticated USING (true);

-- 4. Create trigger function to auto-fill tenant_id from public.dispositivos using serial (device_id)
CREATE OR REPLACE FUNCTION public.fill_price_query_events_tenant_id()
RETURNS TRIGGER AS $$
BEGIN
    SELECT tenant_id INTO NEW.tenant_id
    FROM public.dispositivos
    WHERE serial = NEW.device_id
    LIMIT 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 5. Bind trigger to price_query_events table
DROP TRIGGER IF EXISTS tr_fill_price_query_events_tenant_id ON public.price_query_events;
CREATE TRIGGER tr_fill_price_query_events_tenant_id
    BEFORE INSERT ON public.price_query_events
    FOR EACH ROW
    EXECUTE FUNCTION public.fill_price_query_events_tenant_id();
