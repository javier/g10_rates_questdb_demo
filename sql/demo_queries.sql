-- ============================================================================
-- G10 Rates demo queries (QuestDB)
--
-- A guided tour of the dataset for a live demo. Mirrors the FX demo_queries.sql
-- but tells the rates story: a coherent curve, depth on hedge instruments, and
-- the joint quoting + hedging join at the centre. kdb parallels are noted inline
-- (ASOF JOIN = aj, WINDOW JOIN = wj, SAMPLE BY = xbar).
--
-- Run blocks individually. Most use TICK time filters ($today, $now-1h, ...).
-- ============================================================================

tables();

-- ---------------------------------------------------------------------------
-- 1. Dataset intro
-- ---------------------------------------------------------------------------
SELECT * FROM g10_instruments;                                   -- the universe (dimension)
SELECT * FROM g10_market_data ORDER BY timestamp DESC LIMIT 50;  -- depth firehose
SELECT * FROM g10_core_price   ORDER BY timestamp DESC LIMIT 50; -- BBO + curve mids
SELECT * FROM g10_quotes       ORDER BY timestamp DESC LIMIT 50; -- our quotes
SELECT * FROM g10_rfqs         ORDER BY timestamp DESC LIMIT 50; -- inbound requests
SELECT * FROM g10_trades       ORDER BY timestamp DESC LIMIT 50; -- fills (client + hedge)
SELECT * FROM g10_axes         ORDER BY timestamp DESC LIMIT 50; -- directional interest

-- Volume shape: market_data >> core_price > quotes > trades
SELECT 'market_data' t, count() c FROM g10_market_data
UNION ALL SELECT 'core_price', count() FROM g10_core_price
UNION ALL SELECT 'quotes',     count() FROM g10_quotes
UNION ALL SELECT 'rfqs',       count() FROM g10_rfqs
UNION ALL SELECT 'trades',     count() FROM g10_trades
ORDER BY c DESC;

-- Two quote populations, made explicit by quote_type
SELECT quote_type, count() FROM g10_quotes;

-- ---------------------------------------------------------------------------
-- 2. TICK time filters (declarative intervals, timezones, exchange calendars)
-- ---------------------------------------------------------------------------
SELECT * FROM g10_market_data WHERE timestamp IN '$yesterday';
SELECT * FROM g10_market_data WHERE timestamp IN '$now-1h..$now';
SELECT * FROM g10_trades WHERE instrument_id = 'USD_OIS_10Y' AND timestamp IN '$today' LIMIT -10;
-- US Treasury session (New York exchange calendar)
SELECT * FROM g10_market_data WHERE instrument_id = 'USD_ZN' AND timestamp IN '$yesterday#XNYS' LIMIT -5;

-- ---------------------------------------------------------------------------
-- 3. Depth-of-book arrays (bids/asks are DOUBLE[][] of shape [2][levels])
-- ---------------------------------------------------------------------------
SELECT timestamp, instrument_id,
    bids[1,1] AS bid_px, bids[2,1] AS bid_sz,         -- level 1
    asks[1,1] AS ask_px, asks[2,1] AS ask_sz,
    bids[1,-1] AS worst_bid_px, asks[1,-1] AS worst_ask_px,  -- deepest level
    array_sum(bids[2]) AS total_bid_vol,
    array_sum(asks[2]) AS total_ask_vol
FROM g10_market_data WHERE timestamp IN '$today' LIMIT -20;

-- Array dimensions per side
SELECT timestamp, instrument_id,
    bids[1] AS bid_prices, bids[2] AS bid_sizes, array_count(bids[1]) AS bid_levels,
    asks[1] AS ask_prices, array_count(asks[1]) AS ask_levels
FROM g10_market_data LATEST ON timestamp PARTITION BY instrument_id;

-- ---------------------------------------------------------------------------
-- 4. LATEST ON: current snapshot per group
-- ---------------------------------------------------------------------------
SELECT * FROM g10_core_price LATEST ON timestamp PARTITION BY instrument_id;
SELECT * FROM g10_market_data WHERE instrument_id = 'EUR_FGBL' LATEST ON timestamp PARTITION BY instrument_id;

-- ---------------------------------------------------------------------------
-- 5. The yield curve (the rates-specific realism check)
--    Reconstruct the live curve per currency, ordered by tenor. Should be smooth
--    (no pillar crossings) thanks to the Nelson-Siegel factor walk.
-- ---------------------------------------------------------------------------
SELECT i.ccy, i.tenor, i.tenor_years, round(c.mid, 4) AS rate
FROM (SELECT instrument_id, mid FROM g10_core_price LATEST ON timestamp PARTITION BY instrument_id) c
JOIN g10_instruments i ON c.instrument_id = i.instrument_id
WHERE i.price_space = 'rate'
ORDER BY i.ccy, i.tenor_years;

-- ---------------------------------------------------------------------------
-- 6. Tiering / unified hot + cold reads (the standout: query stitches storage
--    tiers transparently). Recent partitions are native (hot); older ones are
--    parquet (cold), and the same query reads both.
-- ---------------------------------------------------------------------------
table_partitions('g10_market_data');         -- isParquet flag per partition
SELECT name, isParquet FROM table_partitions('g10_market_data');

-- One query spanning hot + cold partitions; join the partition's tier in
WITH parts AS (SELECT name, isParquet FROM table_partitions('g10_core_price')),
     hourly AS (
        SELECT timestamp, instrument_id, avg(mid) AS mid
        FROM g10_core_price WHERE instrument_id = 'USD_OIS_10Y' SAMPLE BY 1h
     )
SELECT h.timestamp, h.mid, p.isParquet AS from_cold_parquet
FROM hourly h JOIN parts p ON to_str(h.timestamp, 'yyyy-MM-ddTHH') = p.name
ORDER BY h.timestamp;

-- ---------------------------------------------------------------------------
-- 7. SAMPLE BY (xbar): curve-mid candles
-- ---------------------------------------------------------------------------
SELECT timestamp, instrument_id,
    first(mid) AS open, max(mid) AS high, min(mid) AS low, last(mid) AS close
FROM g10_core_price
WHERE instrument_id = 'USD_OIS_10Y' AND timestamp IN '$today'
SAMPLE BY 1m;

-- ---------------------------------------------------------------------------
-- 8. Materialized views (engine-maintained rollups + risk)
-- ---------------------------------------------------------------------------
materialized_views();
SELECT * FROM g10_curve_mid_1m WHERE instrument_id = 'USD_OIS_10Y' ORDER BY timestamp DESC LIMIT 10;
SELECT * FROM g10_bbo_1m       ORDER BY timestamp DESC LIMIT 10;
SELECT * FROM g10_pos_risk     ORDER BY timestamp DESC LIMIT 10;   -- DV01 FLOW per 1s bucket

-- ---------------------------------------------------------------------------
-- 9. Positions (stock) and PosRisk (flow vs stock)
--    g10_positions is a VIEW: running net per (book, instrument). g10_pos_risk
--    is the per-interval risk FLOW. Current book DV01 (stock) is derived below.
-- ---------------------------------------------------------------------------
SELECT * FROM g10_positions LATEST ON timestamp PARTITION BY book, instrument_id;

-- Current book DV01 by (ccy, tenor_bucket): positions (stock) x per-unit sensitivity
SELECT i.ccy, i.tenor_bucket,
    sum(p.net_notional * i.dv01_per_unit) AS book_dv01
FROM (SELECT instrument_id, book, net_notional FROM g10_positions
      LATEST ON timestamp PARTITION BY book, instrument_id) p
JOIN g10_instruments i ON p.instrument_id = i.instrument_id
GROUP BY i.ccy, i.tenor_bucket
ORDER BY i.ccy, i.tenor_bucket;

-- ---------------------------------------------------------------------------
-- 10. THE HERO QUERY: joint quoting + hedging in one multi-way ASOF (aj)
--     For each dealt RFQ: prevailing curve mid + current position + live axe ->
--     a skewed quote and the implied hedge notional in the nearest future.
-- ---------------------------------------------------------------------------
WITH pos AS (
    SELECT timestamp, book, instrument_id, net_notional
    FROM g10_positions
    ORDER BY timestamp ASC                 -- re-designates the timestamp for ASOF
)
SELECT
    r.timestamp, r.rfq_id, r.instrument_id, r.ccy, r.side AS client_side, r.notional,
    i.tenor_bucket,
    round(m.mid, 4)                          AS curve_mid,
    p.net_notional                           AS current_pos,
    round(p.net_notional * i.dv01_per_unit, 0) AS current_bucket_dv01,
    a.skew_bps                               AS axe_skew_bps,
    round(m.mid + COALESCE(a.skew_bps, 0) * 0.0001
              + (-p.net_notional / 5.0e8) * 0.0001, 5) AS quoted_mid,
    round(-(p.net_notional * i.dv01_per_unit) / NULLIF(h.dv01_per_unit, 0), 0) AS implied_hedge_notional
FROM g10_rfqs r
ASOF JOIN g10_core_price m  ON (r.instrument_id = m.instrument_id)   -- ASOF first: keeps rfqs' ASC ts
ASOF JOIN pos p             ON (r.instrument_id = p.instrument_id)
JOIN g10_instruments i      ON r.instrument_id = i.instrument_id
LEFT JOIN g10_axes a        ON (r.instrument_id = a.instrument_id)
LEFT JOIN g10_instruments h ON h.instrument_id = (
    CASE i.ccy WHEN 'USD' THEN 'USD_ZN' WHEN 'EUR' THEN 'EUR_FGBL'
               WHEN 'GBP' THEN 'GBP_GLONG' ELSE 'JPY_JGBL' END)
WHERE r.status = 'dealt'
LIMIT 50;

-- ---------------------------------------------------------------------------
-- 11. Streaming-quote adjustment: how our published mid is skewed away from the
--     curve by inventory (the skew_bps column on streaming quotes).
-- ---------------------------------------------------------------------------
SELECT instrument_id, ccy, platform, round(mid,4) AS published_mid, round(skew_bps,3) AS skew_bps
FROM g10_quotes
WHERE quote_type = 'streaming' AND abs(skew_bps) > 0.1
LATEST ON timestamp PARTITION BY instrument_id, platform
ORDER BY abs(skew_bps) DESC;

-- ---------------------------------------------------------------------------
-- 12. WINDOW JOIN (wj): quote density and average mid in a +/-5s window
--     around each RFQ (market context at request time).
-- ---------------------------------------------------------------------------
SELECT r.timestamp, r.rfq_id, r.instrument_id,
    avg(c.mid) AS avg_mid_pm5s, count() AS quotes_pm5s
FROM g10_rfqs r
WINDOW JOIN g10_core_price c ON (r.instrument_id = c.instrument_id)
    RANGE BETWEEN 5 seconds PRECEDING AND 5 seconds FOLLOWING
WHERE r.timestamp IN '$today';

-- ---------------------------------------------------------------------------
-- 13. HORIZON JOIN: markout of client fills against the mid at fixed horizons.
-- ---------------------------------------------------------------------------
SELECT t.instrument_id, h.offset / 1000000000 AS horizon_sec, count() AS n,
    avg((m.mid - t.price)) AS avg_markout
FROM g10_trades t
HORIZON JOIN g10_core_price m ON (t.instrument_id = m.instrument_id)
    LIST (0, 1s, 5s, 30s, 1m) AS h
WHERE t.is_hedge = false AND t.timestamp IN '$today'
GROUP BY t.instrument_id, horizon_sec
ORDER BY t.instrument_id, horizon_sec;

-- ---------------------------------------------------------------------------
-- 14. Order-book analytics on a hedge instrument: how large a hedge can I lift
--     within 2 ticks, and what price does a given clip reach?
-- ---------------------------------------------------------------------------
-- Volume available within ~2 ticks of the best ask
SELECT timestamp, instrument_id,
    asks[2, 1:insertion_point(asks[1], asks[1,1] + 2 * 0.015625)] AS reachable_sizes,
    array_sum(asks[2, 1:insertion_point(asks[1], asks[1,1] + 2 * 0.015625)]) AS reachable_volume
FROM g10_market_data WHERE instrument_id = 'USD_ZN' LATEST ON timestamp PARTITION BY instrument_id;

-- What price level does a buy of 5,000,000 reach?
WITH q AS (
    SELECT timestamp, instrument_id, asks, array_cum_sum(asks[2]) AS cum
    FROM g10_market_data WHERE instrument_id = 'USD_ZN' LATEST ON timestamp PARTITION BY instrument_id)
SELECT timestamp, instrument_id,
    insertion_point(cum, 5000000, true) AS fill_level,
    asks[1, insertion_point(cum, 5000000, true)] AS fill_price
FROM q;

-- ---------------------------------------------------------------------------
-- 15. Spread by tenor (swaps) and bid/ask spread on hedge instruments
-- ---------------------------------------------------------------------------
SELECT i.ccy, i.tenor_years, round(c.ask - c.bid, 5) AS spread
FROM (SELECT instrument_id, bid, ask FROM g10_core_price LATEST ON timestamp PARTITION BY instrument_id) c
JOIN g10_instruments i ON c.instrument_id = i.instrument_id
WHERE i.ccy = 'USD' AND i.price_space = 'rate'
ORDER BY i.tenor_years;

-- ---------------------------------------------------------------------------
-- 16. DEDUP: idempotent re-ingestion (re-runs and backfills are safe)
-- ---------------------------------------------------------------------------
-- g10_trades is DEDUP UPSERT KEYS(timestamp, trade_id); g10_quotes on (timestamp, quote_id).
SELECT count() total, count_distinct(trade_id) distinct_ids FROM g10_trades;
