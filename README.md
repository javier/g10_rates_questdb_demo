# qwp-g10-rates

A synthetic generator for G10 interest-rate market data that ingests into QuestDB
over QWP, the WebSocket binary protocol of the QuestDB Java client. It is the rates
counterpart of the FX/QWP generator and produces an internally consistent dataset
spanning order-book depth, curve mids, quotes, client requests, trades, positions
and risk. The tables are aligned so the central multi-way ASOF query returns
consistent quotes and hedges.

## Instrument universe

76 instruments across four currencies (`USD`, `EUR`, `GBP`, `JPY`), each anchored to its
RFR benchmark (`SOFR`, `ESTR`, `SONIA`, `TONA`). Three asset classes, defined in
`G10Universe`:

| Class | Count | Naming | Members |
|---|---|---|---|
| **OIS swaps** (rate space) | 48 | `{CCY}_OIS_{tenor}` | 12 pillars per currency: 1M, 3M, 6M, 1Y, 2Y, 3Y, 5Y, 7Y, 10Y, 15Y, 20Y, 30Y. The quotable curve; the 2Y-10Y belly is marked most liquid. |
| **Govvies** (price space) | 16 | `{CCY}_{bench}_{tenor}` | On-the-run benchmarks, 2Y/5Y/10Y/30Y per currency: USTs (32nds), Schatz/Bobl/Bund/Buxl, Gilts, JGBs. The hedge leg. |
| **Futures** (price space) | 12 | `{CCY}_{contract}` | USD/CME: `ZT ZF ZN TN ZB UB`; EUR/Eurex: `FGBS FGBM FGBL FGBX`; GBP/ICE: `GLONG`; JPY: `JGBL`. The most liquid hedge; the depth feed. |

USTs and the CME futures quote in 32nds (`1/32`, futures in finer `1/128`-`1/64`
fractions); Bund, Gilt and JGB complexes quote in `0.01`. Per-currency the default hedge
instrument is the 10Y future (`USD_ZN`, `EUR_FGBL`, `GBP_GLONG`, `JPY_JGBL`), which the
hero query uses to imply the hedge clip.

## Curve model

What evolves over time is the yield curve, not each instrument on its own. Each
currency carries a three-factor Nelson-Siegel state (level, slope and
curvature) that advances once per simulated second and is reconstructed into every
pillar (`CurveEngine`). The walk is seeded deterministically and depends only on
`(currency, second)`, so any worker rebuilds the same curve with no shared state.
Each second's closing state becomes the next second's opening state, which keeps the
curve continuous from one second to the next and prevents pillars from crossing.
DV01 is obtained by shifting the constructed curve by one basis point and repricing,
so the risk figures are consistent with the prices and reproducible across runs.

**Demo volatility.** The per-second factor volatilities in `CurveEngine`
(`SIG_LEVEL`, `SIG_SLOPE`, `SIG_CURV`) are tuned up so the curve, candles and risk
visibly move on screen during a live demo (roughly 2bp/min on the belly, a "busy
day"). This is for show, not realism. Lower those constants for a calmer, more
conservative tape.

**Order-book depth.** Each `g10_market_data` snapshot is a coherent book, not fresh
randomness: the level count and per-level sizes are deterministic *continuous* functions of
`(instrument, time)`. Sampled at the ~20+ snapshots/sec each instrument already emits, the
book breathes smoothly sub-second instead of flickering or holding flat for a whole second.
The level count evolves only slowly (the ladder shape is stable, with the odd level
appearing or disappearing), while per-level sizes carry a faster ripple so the bars visibly
move. The half-spread breathes on a liquidity cycle too, so the touch widens and narrows;
that shows on instruments whose tick is fine relative to the spread (govvies, long bonds),
while the most liquid futures stay pinned at one tick, as in reality. Depth is characteristic
per instrument (liquid futures deep, the belly deeper than the wings) and front-loaded
(heaviest at the touch). Setting `--min_levels == --max_levels` pins every book to that fixed
depth: backfill shallow (e.g. 2 levels) when only `count()`/tiering matters, then go live
with `5..20` for the depth dashboard.

## Tables and views

| Object | Type | Role |
|---|---|---|
| `g10_instruments` | base (static) | reference dimension; `dv01_per_unit` seeded from the curve |
| `g10_market_data` | base | full-depth book snapshots (`DOUBLE[][]`) on futures and govvies; the high-volume table |
| `g10_core_price` | base | consolidated best bid/offer and curve mids; the mid source for the central query |
| `g10_quotes` | base | quotes tagged by `quote_type`: `streaming` (the firehose) and `rfq_response` (set `rfq_id`) |
| `g10_trades` | base | client fills and multi-clip hedges swept across the futures book; `TIMESTAMP_NS` |
| `g10_rfqs` | base | inbound client requests |
| `g10_axes` | base | desk directional interest |
| `g10_positions` | regular view | running net position over trades (cumulative, so it cannot be a materialized view) |
| `g10_pos_risk` | materialized view | trade-driven DV01 flow per `(ccy, tenor_bucket, book)` per second |
| `g10_curve_mid_1m`, `g10_bbo_1m` | materialized view | rollups (curve-mid candles, best bid/offer over depth) |

Target volume ordering: `g10_market_data` is the largest table, then `g10_core_price`,
then `g10_quotes`, then `g10_trades`. Trade counts are kept deliberately low.

## Writer pools

Ingestion is split across three independent writer pools, each with its own QWP
senders, so the heavy table never starves the others:

- **market_data** (`--market_data_processes`) the depth firehose; the only pool that
  fans out to multiple WAL writers. One writer keeps the table free of out-of-order
  apply; add writers only if a single one cannot keep up.
- **core_price** (`--core_processes`) the consolidated best bid/offer and curve mids.
- **business** (`--business_processes`, 0 or 1) a single writer for the low-volume
  tables the central query joins: quotes, trades, rfqs and axes.

In faster-than-life each pool resumes from its own table's last timestamp, so an
interrupted backfill continues with no gap or overlap, and a pool already filled to
`--end_ts` is skipped. In real-time every pool advances one simulated second per
wall-clock second, stamped `--realtime_lookahead_secs` ahead (default 2s) so a live
dashboard stays ahead of WAL apply.

## Timestamp resolution

`g10_trades` uses `TIMESTAMP_NS` so that the multiple fills of a single order stay
distinct and `DEDUP UPSERT KEYS(timestamp, trade_id)` stays exact. The other tables
use `TIMESTAMP` (microseconds). ASOF JOIN aligns the two resolutions automatically.

## Notes on the QuestDB objects

These points were confirmed against a running instance and are reflected in the DDL
and in `sql/hero_query.sql`:

1. The `g10_pos_risk` materialized view joins the instruments dimension, so its
   definition includes `WITH BASE g10_trades`.
2. `g10_positions` is built on a window function and does not expose a designated
   timestamp to the ASOF planner on its own. The central query wraps it in
   `ORDER BY timestamp ASC` to re-establish the timestamp. Without this the query
   fails with `right side of time series join has no timestamp`.
3. Current book DV01 is a stock and is derived in the central query from
   `g10_positions` multiplied by `dv01_per_unit`. The `g10_pos_risk` materialized
   view holds risk flow per interval, because a SAMPLE BY materialized view cannot
   hold a running cumulative.

## Retention and storage tiers

Retention is attached only with `--short_ttl`; without it the tables carry no TTL and
nothing is dropped.

- **Open source:** retention is a plain `TTL`. Once a partition ages past the TTL it is
  dropped and the data is gone. Cold / tiered storage is not available on OSS.
- **Enterprise (`--enterprise true`, together with `--short_ttl`):** retention is a
  `STORAGE POLICY` instead. Partitions tier on a schedule (convert to Parquet, upload to
  remote object storage), and `DROP LOCAL` frees the local copy once the data is on
  remote. After the local drop the data is still served, transparently, from remote, so a
  single query spanning hot (local) and cold (remote) partitions just works.

`g10_market_data` carries its own policy, `DROP LOCAL 4 days`: it grows ~830M rows/day, so
this bounds its local footprint while the full history stays queryable on remote. The
smaller tables keep `DROP LOCAL 3 months`, and the materialized views keep a 10-day `TTL`.

## Build and run

```bash
mvn -q clean package              # or: mvn -q compile exec:java -Dexec.args="--help"
./run_backfill.sh                 # faster-than-life backfill of a window
./run_realtime.sh                 # one simulated second per wall-clock second, until Ctrl+C
```

Connection, authentication and high-availability options match the FX generator:
`--hosts h1:9000,h2:9000`, `--tls_insecure`, `--token_file <path>`, store-and-forward,
per-pool WAL backpressure, and timestamp-overlap checks on re-runs.

### QWP client build must match the server build

The QWP wire format differs between released and master builds, and both report
protocol version `1`, so a mismatch is not detected automatically. It shows up as
`unknown msg_kind 0x18` on responses or `invalid column type code: 0x0` on ingestion.
The client version is a Maven property:

```bash
CLIENT_VERSION=1.3.5-SNAPSHOT ./run_backfill.sh   # local server built from master
./run_backfill.sh                                 # Maven Central release / cluster (default)
```

Store-and-forward keeps un-acknowledged frames under `--sf_dir`, keyed by `senderId`
and reused across runs. Frames written for a different server build will replay from
the start and fail, which can hide a corrected pom. The run scripts clear the spill
directory before a backfill. For real-time, keep store-and-forward so a sender can
resume after an outage, and clear the directory once when you change the target
server build.

After a run, confirm row counts with a server-side `SELECT count()` per table. The
generator's `[DONE]` line is an internal counter, and batch errors over the WebSocket
transport arrive asynchronously.

## Dashboards

Two Grafana dashboards, built and deployed by `grafana/deploy_dashboard.py` (and written
to `grafana/g10_dashboard_live.json` / `g10_dashboard_desk.json` for manual import, in
Grafana's portable "export for sharing" form so import prompts for the datasource).

The split is deliberate: Grafana has one refresh interval per dashboard, so the fast live
panels (100ms) and the heavy analytical queries (which cannot tick that fast) live apart.
In the queries below, `${INSTRUMENT}` and `${CCY}` are Grafana template variables and
`$__timeFilter(ts)` is Grafana's time-range macro. A single **hedge-instrument** dropdown
drives the whole live board: depth, microprice, imbalance and candles follow the instrument,
and a chained hidden `CCY` variable makes the yield curve, slopes and key tenors follow that
instrument's currency. The default is `GBP_GLONG` (the Long Gilt future), so the board opens
on GBP. The desk board has its own **currency** dropdown (default `GBP`).

**How the live panels stay live.** In real-time each pool flushes once per simulated second,
but rows are stamped `--realtime_lookahead_secs` (default 2s) ahead of wall-clock, so the
server always holds the next couple of seconds of sub-second snapshots. The `LATEST ON`
panels bound to `timestamp <= now()`, so a 100ms refresh *sweeps* that buffer (the latest
row at or before wall-clock, advancing every refresh) instead of snapping to the future
edge, which would only move once per flush. Together with the continuously-breathing book,
that is what makes depth and microprice glide at sub-second cadence without changing the
flush rate.

### Live (`g10-rates-live`, 100ms)

The execution view. Every panel is `LATEST ON` / `DESC LIMIT` or a materialized-view read,
so it stays cheap at 100ms no matter how much history exists.

**Live `${CCY}` yield curve** — last mid per rate instrument of the selected currency,
joined to the dimension for its tenor, drawn against a log tenor axis (Plotly). Single
currency on purpose: the Plotly y-axis auto-scales to that curve's ~100-120bp span instead
of the ~450bp needed for all four, so the realistic ~2bp/min walk is actually visible as
drift and reshape. The `now()` bound sweeps the lookahead buffer so it advances every
refresh:

```sql
SELECT i.ccy, i.tenor_years, c.mid AS rate
FROM (SELECT instrument_id, mid FROM g10_core_price WHERE timestamp <= now()
      LATEST ON timestamp PARTITION BY instrument_id) c
JOIN g10_instruments i ON c.instrument_id = i.instrument_id
WHERE i.price_space = 'rate' AND i.ccy = '${CCY}'
ORDER BY i.tenor_years;
```

**Market depth — `${INSTRUMENT}`** — the single latest book snapshot, `UNNEST WITH
ORDINALITY` over the price/size rows of the `DOUBLE[][]` so it adapts to whatever level
count the snapshot carries, with a cumulative running sum per side (Plotly depth ladder):

```sql
WITH snap AS (
  SELECT timestamp, bids, asks FROM g10_market_data
  WHERE instrument_id = '${INSTRUMENT}' AND timestamp <= now()
  LATEST ON timestamp PARTITION BY instrument_id
),
bid_lvl AS (
  SELECT 'bid' AS side, p.price AS price, v.vol AS volume
  FROM snap, UNNEST(snap.bids[1]) WITH ORDINALITY p(price, lvl),
             UNNEST(snap.bids[2]) WITH ORDINALITY v(vol, vlvl)
  WHERE p.lvl = v.vlvl
),
ask_lvl AS (
  SELECT 'ask' AS side, p.price AS price, v.vol AS volume
  FROM snap, UNNEST(snap.asks[1]) WITH ORDINALITY p(price, lvl),
             UNNEST(snap.asks[2]) WITH ORDINALITY v(vol, vlvl)
  WHERE p.lvl = v.vlvl
),
bid_cum AS (SELECT side, price, volume, SUM(volume) OVER (ORDER BY price DESC) AS cum_volume FROM bid_lvl),
ask_cum AS (SELECT side, price, volume, SUM(volume) OVER (ORDER BY price ASC)  AS cum_volume FROM ask_lvl)
SELECT * FROM bid_cum UNION ALL SELECT * FROM ask_cum ORDER BY side, price;
```

**`${INSTRUMENT}` price (1m candles)** — OHLC read straight from the materialized view, no
raw scan (candlestick):

```sql
SELECT timestamp AS time, open, high, low, close
FROM g10_curve_mid_1m
WHERE $__timeFilter(timestamp) AND instrument_id = '${INSTRUMENT}';
```

**`${CCY}` curve slopes (bp)** — 2s10s and 5s30s from four `LATEST ON` mids (stat):

```sql
WITH m AS (
  SELECT instrument_id, mid FROM g10_core_price
  WHERE instrument_id IN ('${CCY}_OIS_2Y','${CCY}_OIS_5Y','${CCY}_OIS_10Y','${CCY}_OIS_30Y')
    AND timestamp <= now()
  LATEST ON timestamp PARTITION BY instrument_id
)
SELECT
  round((max(CASE WHEN instrument_id='${CCY}_OIS_10Y' THEN mid END)
       - max(CASE WHEN instrument_id='${CCY}_OIS_2Y'  THEN mid END)) * 100, 1) AS "2s10s bp",
  round((max(CASE WHEN instrument_id='${CCY}_OIS_30Y' THEN mid END)
       - max(CASE WHEN instrument_id='${CCY}_OIS_5Y'  THEN mid END)) * 100, 1) AS "5s30s bp"
FROM m;
```

**Microprice / spread — `${INSTRUMENT}`** — reads the stored scalar `best_bid`/`best_ask`
columns and touches the array only for the level-1 sizes `bids[2][1]`/`asks[2][1]` (stat).
The microprice glides continuously (the top-of-book sizes breathe even when the touch is
pinned); the spread widens and narrows on instruments whose tick is fine relative to it, and
sits at one tick on the most liquid futures, as in reality:

```sql
WITH snap AS (
  SELECT best_bid, best_ask, bids[2][1] AS bid_top, asks[2][1] AS ask_top
  FROM g10_market_data
  WHERE instrument_id = '${INSTRUMENT}' AND timestamp <= now()
  LATEST ON timestamp PARTITION BY instrument_id
)
SELECT round((best_bid * ask_top + best_ask * bid_top) / (bid_top + ask_top), 5) AS "Microprice",
       round(best_ask - best_bid, 5) AS "Spread"
FROM snap;
```

**Depth imbalance — `${INSTRUMENT}`** — total size each side via `array_sum()` on the size
rows, no `UNNEST` or ladder expansion (gauge, -1..+1):

```sql
SELECT round((array_sum(bids[2]) - array_sum(asks[2]))
           / (array_sum(bids[2]) + array_sum(asks[2])), 4) AS "Imbalance"
FROM g10_market_data
WHERE instrument_id = '${INSTRUMENT}' AND timestamp <= now()
LATEST ON timestamp PARTITION BY instrument_id;
```

**Trade + hedge blotter (latest 25)** — straight `DESC LIMIT` tail of the trades table
(table):

```sql
SELECT timestamp AS "Time", instrument_id AS "Instrument", ccy AS "Ccy",
  side AS "Side", notional AS "Notional", round(price, 4) AS "Price",
  client_id AS "Client", book AS "Book"
FROM g10_trades ORDER BY timestamp DESC LIMIT 25;
```

### Desk / Risk (`g10-rates-desk`, auto-refresh off)

The analytical view, defaulting to yesterday's full session (static history, so no point
re-running the heavy queries on a timer), currency `GBP`. Day-scale queries sample at 1m.

**G10 yield curves (as of end of range)** — all four curves overlaid, bounded to the
selected window via `$__timeFilter` + `LATEST ON`: the curve as of the right edge of the
range (yesterday's close for the default, the current curve for trailing 2d/7d ranges). A
static multi-currency overview, which suits an EOD review board (Plotly):

```sql
SELECT i.ccy, i.tenor_years, c.mid AS rate
FROM (SELECT instrument_id, mid FROM g10_core_price WHERE $__timeFilter(timestamp)
      LATEST ON timestamp PARTITION BY instrument_id) c
JOIN g10_instruments i ON c.instrument_id = i.instrument_id
WHERE i.price_space = 'rate'
ORDER BY i.ccy, i.tenor_years;
```

**Joint Quote + Hedge** — the hero query: multi-way ASOF over dealt RFQs joining the curve
mid and the running position, deriving quoted mid (skew off inventory) and the implied
hedge clip in the currency's 10Y future (table). The driver is pre-limited *before* the
joins: `deals` takes the last 25 dealt RFQs in the window (`LIMIT -25` keeps ascending
order, which ASOF needs; a `-50` buffer absorbs the post-join null drop), so the joins run
on ~50 rows instead of every dealt RFQ in the range:

```sql
WITH pos AS (
  SELECT timestamp, book, instrument_id, net_notional FROM g10_positions ORDER BY timestamp ASC
),
deals AS (
  SELECT timestamp, instrument_id, ccy, side, notional
  FROM g10_rfqs
  WHERE $__timeFilter(timestamp) AND status = 'dealt'
  LIMIT -50
)
SELECT d.timestamp AS "Time", d.instrument_id AS "Instrument", d.ccy AS "Ccy",
  d.side AS "Client", d.notional AS "Notional",
  round(m.mid, 4) AS "Curve mid", p.net_notional AS "Position",
  round(p.net_notional * i.dv01_per_unit, 0) AS "Bucket DV01",
  round(m.mid + (-p.net_notional / 5.0e8) * 0.0001, 5) AS "Quoted mid",
  round(-(p.net_notional * i.dv01_per_unit) / NULLIF(h.dv01_per_unit, 0), 0) AS "Implied hedge"
FROM deals d
ASOF JOIN g10_core_price m ON (d.instrument_id = m.instrument_id)
ASOF JOIN pos p ON (d.instrument_id = p.instrument_id)
JOIN g10_instruments i ON d.instrument_id = i.instrument_id
LEFT JOIN g10_instruments h ON h.instrument_id = (
  CASE i.ccy WHEN 'USD' THEN 'USD_ZN' WHEN 'EUR' THEN 'EUR_FGBL'
             WHEN 'GBP' THEN 'GBP_GLONG' ELSE 'JPY_JGBL' END)
WHERE p.net_notional IS NOT NULL
ORDER BY d.timestamp DESC LIMIT 25;
```

**Cumulative position (trading book) DV01 by tenor bucket — `${CCY}`** — the `g10_pos_risk`
matview's per-interval DV01 flow, accumulated with a window sum and pivoted to one column
per bucket (timeseries):

```sql
WITH f AS (
  SELECT timestamp, tenor_bucket, sum(dv01_flow) AS dv01
  FROM g10_pos_risk WHERE $__timeFilter(timestamp) AND ccy = '${CCY}' SAMPLE BY 1m
), c AS (
  SELECT timestamp, tenor_bucket,
    sum(dv01) OVER (PARTITION BY tenor_bucket ORDER BY timestamp) AS cum
  FROM f
)
SELECT timestamp AS time,
  last(CASE WHEN tenor_bucket = '1-3Y'   THEN cum END) AS "1-3Y",
  last(CASE WHEN tenor_bucket = '3-7Y'   THEN cum END) AS "3-7Y",
  last(CASE WHEN tenor_bucket = '7-15Y'  THEN cum END) AS "7-15Y",
  last(CASE WHEN tenor_bucket = '15-30Y' THEN cum END) AS "15-30Y"
FROM c SAMPLE BY 1m;
```

**`${CCY}` curve - key tenors (1m)** — 2Y/5Y/10Y/30Y OIS mids, sampled at 1m (timeseries):

```sql
SELECT timestamp AS time,
  last(CASE WHEN instrument_id = '${CCY}_OIS_2Y'  THEN mid END) AS "${CCY} 2Y",
  last(CASE WHEN instrument_id = '${CCY}_OIS_5Y'  THEN mid END) AS "${CCY} 5Y",
  last(CASE WHEN instrument_id = '${CCY}_OIS_10Y' THEN mid END) AS "${CCY} 10Y",
  last(CASE WHEN instrument_id = '${CCY}_OIS_30Y' THEN mid END) AS "${CCY} 30Y"
FROM g10_core_price
WHERE $__timeFilter(timestamp)
  AND instrument_id IN ('${CCY}_OIS_2Y','${CCY}_OIS_5Y','${CCY}_OIS_10Y','${CCY}_OIS_30Y')
SAMPLE BY 1m;
```

The matview-backed panels (desk DV01, live candles) read views with a 10-day TTL, so the
desk board reaches back about ten days. To change that, edit the TTL in the generator and
rerun `sql/recreate_matviews.sql` against an existing instance: `CREATE MATERIALIZED VIEW
IF NOT EXISTS` will not alter a live view, so the matviews must be dropped and recreated
(recreating triggers a full refresh over the base history).

```bash
python3 grafana/deploy_dashboard.py                # build + push to $GRAFANA_URL
WRITE_ONLY=1 python3 grafana/deploy_dashboard.py   # regenerate the JSON only, no deploy
```

Env: `GRAFANA_URL`, `GRAFANA_USER`, `GRAFANA_PASS`, `QDB_DS_UID`.

## Validation

`sql/validation.sql` checks the dataset: volume ordering, curve shape by currency,
second-to-second continuity, DV01 sanity, the flow versus stock distinction, and the
output of the central query (`sql/hero_query.sql`).

## Layout

```
pom.xml
src/main/java/com/questdb/g10qwp/
  Instrument.java       one instrument (curve point or hedge), with tenor in years and a bucket
  G10Universe.java      the instrument set (swaps, govvies, futures), clients, size ladders
  CurveEngine.java      deterministic per-currency Nelson-Siegel walk and bump-reprice DV01
  YahooRates.java       optional live anchor fetch (the walk stays deterministic regardless)
  G10Cli.java           command-line options
  G10Generator.java     pools, per-second walk, DDL and views, QWP sender, backpressure
sql/hero_query.sql      the multi-way ASOF query, with a WINDOW JOIN companion
sql/validation.sql      the validation queries
sql/recreate_matviews.sql   drop + recreate the matviews (e.g. to change their TTL)
grafana/deploy_dashboard.py   builds + deploys the two dashboards; writes the JSON
grafana/g10_dashboard_live.json, g10_dashboard_desk.json   importable dashboards
run_backfill.sh, run_realtime.sh
```
