# qwp-g10-rates

A synthetic generator for G10 interest-rate market data that ingests into QuestDB
over QWP, the WebSocket binary protocol of the QuestDB Java client. It is the rates
counterpart of the FX/QWP generator and produces an internally consistent dataset
spanning order-book depth, curve mids, quotes, client requests, trades, positions
and risk. The tables are aligned so the central multi-way ASOF query returns
consistent quotes and hedges.

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

## Tables and views

| Object | Type | Role |
|---|---|---|
| `g10_instruments` | base (static) | reference dimension; `dv01_per_unit` seeded from the curve |
| `g10_market_data` | base | full-depth book snapshots (`DOUBLE[][]`) on futures and govvies; the high-volume table |
| `g10_core_price` | base | consolidated best bid/offer and curve mids; the mid source for the central query |
| `g10_quotes` | base | quotes: streaming (null `rfq_id`) and RFQ responses (non-null `rfq_id`) |
| `g10_trades` | base | client fills and hedges; `TIMESTAMP_NS` |
| `g10_rfqs` | base | inbound client requests |
| `g10_axes` | base | desk directional interest |
| `g10_positions` | regular view | running net position over trades (cumulative, so it cannot be a materialized view) |
| `g10_pos_risk` | materialized view | trade-driven DV01 flow per `(ccy, tenor_bucket, book)` per second |
| `g10_curve_mid_1m`, `g10_bbo_1m` | materialized view | rollups (curve-mid candles, best bid/offer over depth) |

Target volume ordering: `g10_market_data` is the largest table, then `g10_core_price`,
then `g10_quotes`, then `g10_trades`. Trade counts are kept deliberately low.

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
run_backfill.sh, run_realtime.sh
```
