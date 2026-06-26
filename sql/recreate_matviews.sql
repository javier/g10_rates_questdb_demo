-- ============================================================================
-- Recreate the three materialized views with the longer (10-day) TTL.
--
-- CREATE MATERIALIZED VIEW IF NOT EXISTS does NOT alter an existing view, so the
-- old 3-day TTL would stick. Drop and recreate: on CREATE, the IMMEDIATE views do a
-- full refresh over the base table's existing data, so they repopulate the whole
-- history (then keep the last 10 days under the new TTL).
--
-- This matches the generator DDL for a no-suffix, --short_ttl run. Drop the
-- "TTL 10 DAYS" if you run without --short_ttl; on enterprise the matviews still take
-- TTL (not a storage policy), so keep it.
-- ============================================================================

DROP MATERIALIZED VIEW IF EXISTS g10_pos_risk;
CREATE MATERIALIZED VIEW IF NOT EXISTS 'g10_pos_risk' WITH BASE 'g10_trades' REFRESH IMMEDIATE AS (
  SELECT t.timestamp, t.book, i.ccy, i.tenor_bucket,
    sum((CASE WHEN t.side='buy' THEN 1 ELSE -1 END) * t.notional * i.dv01_per_unit) AS dv01_flow
  FROM g10_trades t JOIN g10_instruments i ON t.instrument_id = i.instrument_id
  SAMPLE BY 1s
) PARTITION BY HOUR TTL 10 DAYS;

DROP MATERIALIZED VIEW IF EXISTS g10_curve_mid_1m;
CREATE MATERIALIZED VIEW IF NOT EXISTS 'g10_curve_mid_1m' WITH BASE 'g10_core_price' REFRESH IMMEDIATE AS (
  SELECT timestamp, instrument_id, first(mid) AS open, max(mid) AS high,
    min(mid) AS low, last(mid) AS close
  FROM g10_core_price SAMPLE BY 1m
) PARTITION BY HOUR TTL 10 DAYS;

DROP MATERIALIZED VIEW IF EXISTS g10_bbo_1m;
CREATE MATERIALIZED VIEW IF NOT EXISTS 'g10_bbo_1m' WITH BASE 'g10_market_data' REFRESH EVERY 1m AS (
  SELECT timestamp, instrument_id, max(best_bid) AS bid, min(best_ask) AS ask
  FROM g10_market_data SAMPLE BY 1m
) PARTITION BY DAY TTL 10 DAYS;
