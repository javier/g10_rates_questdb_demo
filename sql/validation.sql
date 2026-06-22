-- ============================================================================
-- G10 Rates — validation suite (spec §13.9). Run after a generation pass to
-- prove the data is realistic before showing it to ex-kdb quants.
-- ============================================================================

-- 1. Volume shape: market_data >> core_price > quotes > trades. A quant squints
--    at quotes ~ trades or quotes ~ market_data; this ordering should hold.
SELECT 'market_data' t, count() c FROM g10_market_data
UNION ALL SELECT 'core_price', count() FROM g10_core_price
UNION ALL SELECT 'quotes',     count() FROM g10_quotes
UNION ALL SELECT 'trades',     count() FROM g10_trades
UNION ALL SELECT 'rfqs',       count() FROM g10_rfqs
UNION ALL SELECT 'axes',       count() FROM g10_axes
ORDER BY c DESC;

-- 2. Curve coherence: per-ccy curve mids should be smooth and ordered sanely by
--    tenor (no nonsensical crossings/kinks). Eyeball the latest curve per ccy.
SELECT i.ccy, i.tenor_years, i.tenor, c.mid
FROM g10_core_price c
JOIN g10_instruments i ON c.instrument_id = i.instrument_id
WHERE i.price_space = 'rate'
LATEST ON timestamp PARTITION BY c.instrument_id
ORDER BY i.ccy, i.tenor_years;

-- 3. Continuity: curve-mid 1s series per instrument should have no discontinuity
--    jumps relative to its own move scale (betas carry close->open). Largest 1s
--    jump per instrument — expect small, no outliers.
WITH s AS (
    SELECT timestamp, instrument_id, last(mid) AS m
    FROM g10_core_price SAMPLE BY 1s
), d AS (
    SELECT instrument_id, m - lag(m) OVER (PARTITION BY instrument_id ORDER BY timestamp) AS jump
    FROM s
)
SELECT instrument_id, max(abs(jump)) AS max_1s_jump
FROM d GROUP BY instrument_id ORDER BY max_1s_jump DESC LIMIT 15;

-- 4. DV01 sanity: seed dv01_per_unit should rise with tenor for swaps and be
--    positive everywhere. Spot-check the seed sensitivities.
SELECT ccy, product, tenor, tenor_years, dv01_per_unit
FROM g10_instruments
WHERE price_space = 'rate'
ORDER BY ccy, tenor_years;

-- 5. pos_risk MV is FLOW (per-second risk traded), positions view is STOCK
--    (running net). Confirm they differ in grain (this is §14.1, by design).
SELECT * FROM g10_pos_risk LATEST ON timestamp PARTITION BY ccy, tenor_bucket, book;

-- 6. Hero query smoke: a handful of dealt RFQs should produce sane skews and
--    hedge notionals (right sign, plausible magnitude). See sql/hero_query.sql.
