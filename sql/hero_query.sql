-- ============================================================================
-- G10 Rates — the hero query (demo centrepiece)
--
-- For each inbound RFQ, as-of join the prevailing curve mid, the current book
-- position, the live axe, and the per-bucket risk, then compute a skewed quote
-- and the implied hedge notional. One statement, multi-way ASOF — native in
-- QuestDB (aj/wj in kdb), painful in plain SQL.
--
-- VALIDATED CORRECTIONS baked in (proven on the live instance):
--   * g10_positions is a window-function VIEW, so it does NOT auto-expose a
--     designated timestamp to the ASOF planner. We wrap it in `ORDER BY ts ASC`
--     to re-designate it. Without this you get:
--         "right side of time series join has no timestamp".
--   * Current book DV01 is STOCK, derived here from g10_positions x dv01_per_unit
--     (NOT from the g10_pos_risk MV, which is per-interval FLOW — see §14.1).
-- ============================================================================

WITH pos AS (
    SELECT timestamp, book, instrument_id, net_notional
    FROM g10_positions
    ORDER BY timestamp ASC          -- re-designates `timestamp` for the ASOF join
)
SELECT
    r.timestamp,
    r.rfq_id,
    r.instrument_id,
    r.ccy,
    r.side                              AS client_side,
    r.notional,
    i.tenor_bucket,
    m.mid                               AS curve_mid,
    p.net_notional                      AS current_pos,
    p.net_notional * i.dv01_per_unit    AS current_bucket_dv01,
    a.skew_bps                          AS axe_skew_bps,
    -- Quote mid skewed by the live axe + our inventory (inline, no UDFs):
    m.mid
      + COALESCE(a.skew_bps, 0) * 0.0001
      + (-p.net_notional / 5.0e8) * 0.0001            AS quoted_mid,
    -- Implied hedge: offset the bucket DV01 in the nearest liquid future.
    -(p.net_notional * i.dv01_per_unit) / NULLIF(h.dv01_per_unit, 0) AS implied_hedge_notional
FROM g10_rfqs r
JOIN g10_instruments i        ON r.instrument_id = i.instrument_id
ASOF JOIN g10_core_price m    ON (r.instrument_id = m.instrument_id)
ASOF JOIN pos p               ON (r.instrument_id = p.instrument_id)
LEFT JOIN g10_axes a          ON (r.instrument_id = a.instrument_id)
LEFT JOIN g10_instruments h   ON h.instrument_id = (
    -- nearest hedge future in the same ccy (precomputed in the generator; shown
    -- here as the simplest illustrative pick — the front 10Y future per ccy).
    CASE i.ccy WHEN 'USD' THEN 'USD_ZN' WHEN 'EUR' THEN 'EUR_FGBL'
               WHEN 'GBP' THEN 'GBP_GLONG' ELSE 'JPY_JGBL' END)
WHERE r.status = 'dealt'
LIMIT 50;

-- ----------------------------------------------------------------------------
-- WINDOW JOIN companion (the wj/wj1 half of the kdb story): quote density and
-- average mid in a +/-5s window around each RFQ.
-- ----------------------------------------------------------------------------
-- SELECT r.timestamp, r.rfq_id, r.instrument_id,
--        avg(c.mid) AS avg_mid_pm5s, count() AS quotes_pm5s
-- FROM g10_rfqs r
-- WINDOW JOIN g10_core_price c ON (r.instrument_id = c.instrument_id)
--     RANGE BETWEEN 5 seconds PRECEDING AND 5 seconds FOLLOWING
-- WHERE r.timestamp IN '$now-1h..$now';
