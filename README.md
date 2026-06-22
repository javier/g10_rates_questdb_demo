# qwp-g10-rates

A synthetic **G10 interest-rate** market-data generator that ingests into QuestDB
over **QWP** (the WebSocket binary protocol of the QuestDB Java client), HA-aware —
the rates sibling of the FX/QWP generator that powers `demo.questdb.io`.

It builds a realistic, internally-consistent rates dataset for the "QuestDB as a
KDB/KX alternative" demo: ticking depth on liquid hedge instruments, a coherent
swap curve, streaming quotes, RFQs, trades, positions and risk — all wired so the
multi-way **ASOF JOIN** hero query produces sane skews and implied hedges.

## The load-bearing idea (why it's both fast and realistic)

The entity that random-walks is the **curve, not the instrument**. Each currency's
curve is a 3-factor **Nelson-Siegel** state `(level, slope, curvature)` walked once
per data-second and reconstructed into every pillar (`CurveEngine`). Because the
walk is **deterministic in `(ccy, second)`**, every worker in every process
reconstructs the identical curve with no shared state — so depth, mids, quotes,
trades and the **bump-and-reprice DV01** all derive from one curve and the hero
join is coherent *by construction*. Continuity holds because each second's close
betas carry forward as the next second's open (no curve kinks or crossings).

## The 8 objects

| Object | Type | Role |
|---|---|---|
| `g10_instruments` | base (static) | reference dimension; seed `dv01_per_unit` from bump-reprice |
| `g10_market_data` | base | full-depth book snapshots (`DOUBLE[][]`) on futures/govvies — the firehose |
| `g10_core_price` | base | consolidated BBO / curve mids (the hero-query mid source) |
| `g10_quotes` | base | streaming quotes (change-only), skewed by axe + inventory; `rfq_id` null = streaming |
| `g10_trades` | base | client fills + hedges (honestly low volume); `TIMESTAMP_NS` |
| `g10_rfqs` | base | inbound client requests |
| `g10_axes` | base | desk directional interest |
| `g10_positions` | **regular VIEW** | running signed net over trades (cumulative — cannot be an MV) |
| `g10_pos_risk` | **MATERIALIZED VIEW** | trade-driven DV01 **flow** per `(ccy, tenor_bucket, book)` per 1s |
| `g10_curve_mid_1m`, `g10_bbo_1m` | MV | rollups (curve-mid candles IMMEDIATE; BBO over depth on a TIMER) |

Volume target shape (a quant nods at this ordering):
`market_data ≫ core_price > quotes > trades`.

## Timestamp resolution (deliberate mix)

`g10_trades` is `TIMESTAMP_NS` (order→multi-fill bursts, clean `(ts, trade_id)`
dedup); everything else is `TIMESTAMP` (micros). ASOF JOIN aligns the precisions
automatically.

## Validated design corrections (proven on a live 9.4.3 instance)

1. **`g10_pos_risk` MV needs `WITH BASE g10_trades`** — it joins the instruments
   dimension, and current QuestDB requires the base table to be named for a
   join-bearing MV.
2. **The hero query wraps the positions side in `ORDER BY timestamp ASC`** — a
   window-function view does not auto-expose a designated timestamp to the ASOF
   planner (`right side of time series join has no timestamp`); the `ORDER BY`
   re-designates it. See `sql/hero_query.sql`.
3. **Stock vs flow (§14.1):** current book DV01 is derived in the hero query from
   `g10_positions × dv01_per_unit` (stock). The `g10_pos_risk` MV is per-second
   risk *flow* — a `SAMPLE BY` MV cannot be cumulative.

## Build & run

```bash
mvn -q clean package         # or: mvn -q compile exec:java -Dexec.args="--help"
./run_backfill.sh            # faster-than-life: a day (Profile A) — widen the window for a month (B)
./run_realtime.sh            # one data-second per wall-second, until Ctrl+C
```

Connection / auth / HA mirror the FX generator: `--hosts h1:9000,h2:9000`,
`--tls_insecure`, `--token_file <path>`, store-and-forward on, per-pool WAL
backpressure with hysteresis, timestamp-overlap safety on (re)runs.

### ⚠️ QWP client build must match the server build

QWP is open source, but the **wire protocol differs between releases and master**,
and both report protocol version `1`, so a skew can't be auto-detected — it just
misparses (`unknown msg_kind 0x18` on responses, `invalid column type code: 0x0` on
ingestion). The client version is a pom property:

```bash
# Against a local server built from master (java-questdb-client installed via mvn install):
CLIENT_VERSION=1.3.5-SNAPSHOT ./run_backfill.sh
# Against the Maven-Central release / cluster (default):
./run_backfill.sh
```

**Store-and-forward poison-replay:** the WS sender persists un-ACKed frames under
`--sf_dir` (keyed by `senderId`, reused across runs). Frames written for a different
server build replay from offset 0 and die at `seq=0` — masking a fixed pom. The run
scripts clear the SF dir before a backfill; for real-time keep SF (buffer→resume on a
primary blip) but clear it once when you change the target server build.

**Always verify server-side** (`SELECT count()` per table) — the generator's `[DONE]`
line is just its internal counter, and WS batch errors arrive asynchronously.

## Validation

After a run, `sql/validation.sql` proves the dataset to ex-kdb quants: volume
shape, curve coherence, 1s continuity, DV01 sanity, flow-vs-stock, and the hero
query producing sane skews + hedge notionals.

## Layout

```
pom.xml
src/main/java/com/questdb/g10qwp/
  Instrument.java       one instrument (curve point or hedge), tenor_years + bucket
  G10Universe.java      the universe (swaps + govvies + futures), clients, ladders
  CurveEngine.java      deterministic per-ccy Nelson-Siegel walk + bump-reprice DV01
  YahooRates.java       optional live US-anchor (garnish; walk stays deterministic)
  G10Cli.java           flag surface (Python underscore / kebab forms)
  G10Generator.java     the engine: pools, per-second walk, DDL/MVs, QWP sender, backpressure
sql/hero_query.sql      the multi-way ASOF centrepiece (+ WINDOW JOIN companion)
sql/validation.sql      the §13.9 validation suite
run_backfill.sh / run_realtime.sh
```
