# Scope: Milestone 1 (R1: Parse and store license)

## Architecture
- `DeviceCache` (data model for cached device details)
- `DeviceCacheManager` (manages preference-based storage for `DeviceCache`)
- `DeviceValidationService` (calls remote RPC to validate device and parses response into `DeviceCache`)
- `SettingsActivity` (saves settings, needs compatibility with updated `DeviceCache`)

## Interface Contracts
- `DeviceCache` has an optional field `tipoDaLicenca: String?`
- Preference storage has `"tipo_da_licenca"` string key
- Supabase response contains `"tipo_da_licenca"` string/null

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | R1 Parse & Store | Parse `tipo_da_licenca` and update cache / persistence | none | DONE |
