-- Add descricao column to price_query_events table
ALTER TABLE public.price_query_events ADD COLUMN IF NOT EXISTS descricao TEXT;
