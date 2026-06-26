#!/usr/bin/env python3
"""Build and deploy the G10 Rates Grafana dashboard.

Mirrors the FX "Orderbook Realtime" dashboard but tells the rates story:
a live yield curve, depth on a hedge instrument, the joint quote+hedge join,
the curve ticking, and current book risk by bucket.

Usage:
    python3 grafana/deploy_dashboard.py
Env overrides: GRAFANA_URL, GRAFANA_USER, GRAFANA_PASS, QDB_DS_UID.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

GRAFANA_URL = os.environ.get("GRAFANA_URL", "http://localhost:3000")
GRAFANA_USER = os.environ.get("GRAFANA_USER", "admin")
GRAFANA_PASS = os.environ.get("GRAFANA_PASS", "ornitorrinco")
DS_UID = os.environ.get("QDB_DS_UID", "adf6db91-a92a-4a5a-b814-93c8c5aa4240")

DS = {"type": "questdb-questdb-datasource", "uid": DS_UID}


def target(sql, ref="A", fmt=1):
    return {"datasource": DS, "format": fmt, "queryType": "sql",
            "rawSql": sql, "refId": ref, "selectedFormat": 2}


# Depth ladder rawSql. UNNEST the bid/ask arrays WITH ORDINALITY so it adapts to
# whatever number of levels each snapshot carries (5, 20, anything) with no hardcoded
# level count -- the book depth can vary snapshot to snapshot and the chart follows.
DEPTH_SQL = """WITH snap AS (
  SELECT timestamp, bids, asks FROM g10_market_data
  WHERE instrument_id = '${INSTRUMENT}'
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
ask_cum AS (SELECT side, price, volume, SUM(volume) OVER (ORDER BY price ASC) AS cum_volume FROM ask_lvl)
SELECT * FROM bid_cum UNION ALL SELECT * FROM ask_cum ORDER BY side, price;"""


# Plotly script for the depth panel (cumulative bid/ask volume + liquidity walls).
DEPTH_SCRIPT = r"""
const table = data.series[0];
const fields = table.fields;
const side = fields.find(f => f.name === "side");
const price = fields.find(f => f.name === "price");
const cum_volume = fields.find(f => f.name === "cum_volume");
const volume = fields.find(f => f.name === "volume");
if (!side || !price || !cum_volume || !volume) { throw new Error("Missing fields"); }
const sideVals = side.values.toArray();
const priceVals = price.values.toArray();
const cumVolVals = cum_volume.values.toArray();
const volVals = volume.values.toArray();
const bids = [], asks = [];
for (let i = 0; i < priceVals.length; i++) {
  const s = sideVals[i], p = priceVals[i], v = cumVolVals[i], raw = volVals[i];
  if (s === "bid") bids.push({ x: p, y: v, raw }); else if (s === "ask") asks.push({ x: p, y: v, raw });
}
bids.sort((a, b) => b.x - a.x); asks.sort((a, b) => a.x - b.x);
const bestBid = bids[0]?.x ?? 0, bestAsk = asks[0]?.x ?? 0, mid = (bestBid + bestAsk) / 2;
if (bids.length > 0) bids.unshift({ x: mid, y: bids[0].y });
if (asks.length > 0) asks.unshift({ x: mid, y: 0 });
const topBids = [...bids].filter(b => b.raw).sort((a, b) => b.raw - a.raw).slice(-2);
const topAsks = [...asks].filter(a => a.raw).sort((a, b) => b.raw - a.raw).slice(-2);
const wallLines = [...topBids, ...topAsks].map(w => ({ type: "line", x0: w.x, x1: w.x, y0: 0, y1: 1,
  xref: "x", yref: "paper", line: { color: "yellow", width: 1, dash: "dot" } }));
const wallLabels = [...topBids, ...topAsks].map(w => ({ x: w.x, y: 1.02, xref: "x", yref: "paper",
  text: w.x.toFixed(4), showarrow: false, font: { color: "yellow", size: 10 } }));
// Pin the x-range to the full book span so every instrument (any tick size / price
// level) shows all levels, not just a sliver near the mid.
const allX = [...bids, ...asks].map(p => p.x);
const xMin = Math.min.apply(null, allX), xMax = Math.max.apply(null, allX);
const xPad = (xMax - xMin) * 0.04 || 0.01;
return {
  data: [
    { name: "Bids", x: bids.map(p => p.x), y: bids.map(p => p.y), mode: "lines", fill: "tozeroy",
      fillcolor: "rgba(0,255,0,0.2)", line: { shape: "hv", color: "rgba(0,255,0,0.7)", width: 2 },
      type: "scatter", hovertemplate: "Px: %{x}<br>CumVol: %{y}<extra></extra>" },
    { name: "Asks", x: asks.map(p => p.x), y: asks.map(p => p.y), mode: "lines", fill: "tozeroy",
      fillcolor: "rgba(255,0,0,0.2)", line: { shape: "hv", color: "rgba(255,0,0,0.7)", width: 2 },
      type: "scatter", hovertemplate: "Px: %{x}<br>CumVol: %{y}<extra></extra>" }
  ],
  layout: { plot_bgcolor: "black", paper_bgcolor: "black", font: { color: "white" },
    xaxis: { title: "Price", showgrid: true, gridcolor: "rgba(255,255,255,0.1)", zeroline: false },
    yaxis: { title: "Cumulative Volume", showgrid: true, gridcolor: "rgba(255,255,255,0.1)", zeroline: false },
    margin: { t: 40, l: 60, r: 20, b: 50 },
    shapes: [{ type: "line", x0: mid, x1: mid, y0: 0, y1: 1, xref: "x", yref: "paper",
      line: { color: "white", width: 1, dash: "dot" } }, ...wallLines],
    annotations: wallLabels, legend: { orientation: "h", x: 0.5, xanchor: "center", y: -0.3 } }
};
"""

# Plotly script for the live yield curve (one line per currency, log tenor axis).
CURVE_SCRIPT = r"""
const t = data.series[0], f = t.fields;
const ccy = f.find(x => x.name === "ccy").values.toArray();
const ten = f.find(x => x.name === "tenor_years").values.toArray();
const rate = f.find(x => x.name === "rate").values.toArray();
const byCcy = {};
for (let i = 0; i < ccy.length; i++) {
  (byCcy[ccy[i]] = byCcy[ccy[i]] || { x: [], y: [] });
  byCcy[ccy[i]].x.push(ten[i]); byCcy[ccy[i]].y.push(rate[i]);
}
const colors = { USD: "#4FC3F7", EUR: "#FFB74D", GBP: "#81C784", JPY: "#E57373" };
const traces = Object.keys(byCcy).map(c => ({
  name: c, x: byCcy[c].x, y: byCcy[c].y, mode: "lines+markers", type: "scatter",
  line: { width: 2, color: colors[c] || "white" }, marker: { size: 5 }
}));
return {
  data: traces,
  layout: { plot_bgcolor: "black", paper_bgcolor: "black", font: { color: "white" },
    showlegend: false,
    xaxis: { title: "Tenor", type: "log", tickmode: "array",
      tickvals: [0.25, 0.5, 1, 2, 3, 5, 7, 10, 20, 30],
      ticktext: ["3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y"],
      showgrid: true, gridcolor: "rgba(255,255,255,0.1)" },
    yaxis: { title: "Rate (%)", showgrid: true, gridcolor: "rgba(255,255,255,0.1)" },
    margin: { t: 20, l: 50, r: 20, b: 40 } }
};
"""

CURVE_SQL = """SELECT i.ccy, i.tenor_years, c.mid AS rate
FROM (SELECT instrument_id, mid FROM g10_core_price LATEST ON timestamp PARTITION BY instrument_id) c
JOIN g10_instruments i ON c.instrument_id = i.instrument_id
WHERE i.price_space = 'rate' AND i.ccy = '${CCY}'
ORDER BY i.tenor_years;"""

TENORS_SQL = """SELECT timestamp AS time,
  last(CASE WHEN instrument_id = '${CCY}_OIS_2Y'  THEN mid END) AS "${CCY} 2Y",
  last(CASE WHEN instrument_id = '${CCY}_OIS_5Y'  THEN mid END) AS "${CCY} 5Y",
  last(CASE WHEN instrument_id = '${CCY}_OIS_10Y' THEN mid END) AS "${CCY} 10Y",
  last(CASE WHEN instrument_id = '${CCY}_OIS_30Y' THEN mid END) AS "${CCY} 30Y"
FROM g10_core_price
WHERE $__timeFilter(timestamp)
  AND instrument_id IN ('${CCY}_OIS_2Y','${CCY}_OIS_5Y','${CCY}_OIS_10Y','${CCY}_OIS_30Y')
SAMPLE BY 1m;"""

HERO_SQL = """WITH pos AS (
  SELECT timestamp, book, instrument_id, net_notional FROM g10_positions ORDER BY timestamp ASC
)
SELECT r.timestamp AS "Time", r.instrument_id AS "Instrument", r.ccy AS "Ccy",
  r.side AS "Client", r.notional AS "Notional",
  round(m.mid, 4) AS "Curve mid", p.net_notional AS "Position",
  round(p.net_notional * i.dv01_per_unit, 0) AS "Bucket DV01",
  round(m.mid + (-p.net_notional / 5.0e8) * 0.0001, 5) AS "Quoted mid",
  round(-(p.net_notional * i.dv01_per_unit) / NULLIF(h.dv01_per_unit, 0), 0) AS "Implied hedge"
FROM g10_rfqs r
ASOF JOIN g10_core_price m ON (r.instrument_id = m.instrument_id)
ASOF JOIN pos p ON (r.instrument_id = p.instrument_id)
JOIN g10_instruments i ON r.instrument_id = i.instrument_id
LEFT JOIN g10_instruments h ON h.instrument_id = (
  CASE i.ccy WHEN 'USD' THEN 'USD_ZN' WHEN 'EUR' THEN 'EUR_FGBL'
             WHEN 'GBP' THEN 'GBP_GLONG' ELSE 'JPY_JGBL' END)
WHERE $__timeFilter(r.timestamp) AND r.status = 'dealt' AND p.net_notional IS NOT NULL
ORDER BY r.timestamp DESC LIMIT 20;"""

# Candles read the engine-maintained 1m OHLC materialized view (g10_curve_mid_1m)
# rather than scanning raw core_price -- faster, and it showcases the MV.
# g10_curve_mid_1m is OHLC of core_price.mid for EVERY instrument, so it already holds
# candles for the selected hedge instrument (e.g. ZN's price) -- no extra view needed.
CANDLES_SQL = """SELECT timestamp AS time, open, high, low, close
FROM g10_curve_mid_1m
WHERE $__timeFilter(timestamp) AND instrument_id = '${INSTRUMENT}';"""

# Cumulative book DV01 by bucket over the visible window, pivoted to one column per
# bucket so each draws as its own line (off the g10_pos_risk MV).
RISK_TS_SQL = """WITH f AS (
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
FROM c SAMPLE BY 1m;"""

TS_DEFAULTS = {"color": {"mode": "palette-classic"},
               "custom": {"drawStyle": "line", "lineWidth": 2, "fillOpacity": 0,
                          "showPoints": "never", "spanNulls": True, "axisPlacement": "auto"}}


def plotly_panel(pid, title, sql, script, gp, repeat=None):
    p = {"id": pid, "type": "ae3e-plotly-panel", "title": title, "datasource": DS,
         "gridPos": gp, "pluginVersion": "0.5.0",
         "options": {"script": script, "onclick": ""},
         "targets": [target(sql)]}
    if repeat:
        p["repeat"] = repeat
        p["repeatDirection"] = "h"
    return p


def table_panel(pid, title, sql, gp):
    return {"id": pid, "type": "table", "title": title, "datasource": DS, "gridPos": gp,
            "options": {"showHeader": True, "cellHeight": "sm"},
            "fieldConfig": {"defaults": {"custom": {"align": "auto",
                            "cellOptions": {"type": "auto"}, "minWidth": 60}}, "overrides": []},
            "targets": [target(sql)]}


def ts_panel(pid, title, sql, gp):
    return {"id": pid, "type": "timeseries", "title": title, "datasource": DS, "gridPos": gp,
            "fieldConfig": {"defaults": TS_DEFAULTS, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                        "tooltip": {"mode": "multi", "sort": "none"}},
            "targets": [target(sql)]}


def candle_panel(pid, title, sql, gp, time_from=None):
    p = {"id": pid, "type": "candlestick", "title": title, "datasource": DS, "gridPos": gp,
         "fieldConfig": {"defaults": {"custom": {"axisPlacement": "auto"}}, "overrides": []},
         "options": {"mode": "candles", "candleStyle": "candles", "colorStrategy": "open-close",
                     "colors": {"up": "green", "down": "red"},
                     "fields": {"open": "open", "high": "high", "low": "low", "close": "close"},
                     "legend": {"showLegend": False}},
         "targets": [target(sql)]}
    if time_from:
        p["timeFrom"] = time_from
        p["hideTimeOverride"] = True
    return p


def stat_panel(pid, title, sql, gp, decimals=2):
    return {"id": pid, "type": "stat", "title": title, "datasource": DS, "gridPos": gp,
            "fieldConfig": {"defaults": {"unit": "none", "decimals": decimals,
                            "color": {"mode": "thresholds"},
                            "thresholds": {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}},
                            "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": "value", "graphMode": "none", "textMode": "value_and_name",
                        "justifyMode": "center", "orientation": "horizontal"},
            "targets": [target(sql)]}


def gauge_panel(pid, title, sql, gp):
    return {"id": pid, "type": "gauge", "title": title, "datasource": DS, "gridPos": gp,
            "fieldConfig": {"defaults": {"min": -1, "max": 1, "unit": "none", "decimals": 3,
                            "color": {"mode": "thresholds"},
                            "thresholds": {"mode": "absolute", "steps": [
                                {"color": "red", "value": None},
                                {"color": "orange", "value": -0.4},
                                {"color": "green", "value": -0.15},
                                {"color": "orange", "value": 0.15},
                                {"color": "red", "value": 0.4}]}},
                            "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "showThresholdLabels": False, "showThresholdMarkers": True, "orientation": "auto"},
            "targets": [target(sql)]}


# --- Snappy live-board SQL: every query is LATEST ON / DESC LIMIT, so it stays cheap no
# matter how much history exists and ticks happily at 100ms. ---
SLOPE_SQL = """WITH m AS (
  SELECT instrument_id, mid FROM g10_core_price
  WHERE instrument_id IN ('${CCY}_OIS_2Y','${CCY}_OIS_5Y','${CCY}_OIS_10Y','${CCY}_OIS_30Y')
  LATEST ON timestamp PARTITION BY instrument_id
)
SELECT
  round((max(CASE WHEN instrument_id='${CCY}_OIS_10Y' THEN mid END)
       - max(CASE WHEN instrument_id='${CCY}_OIS_2Y'  THEN mid END)) * 100, 1) AS "2s10s bp",
  round((max(CASE WHEN instrument_id='${CCY}_OIS_30Y' THEN mid END)
       - max(CASE WHEN instrument_id='${CCY}_OIS_5Y'  THEN mid END)) * 100, 1) AS "5s30s bp"
FROM m;"""

# Microprice only needs the top of book. best_bid / best_ask are stored as scalar
# columns, so read those directly (no array load); the array is touched only for the
# top-of-book size (bids[2]/asks[2] are the size rows, [..][1] is level 1).
MICRO_SQL = """WITH snap AS (
  SELECT best_bid, best_ask, bids[2][1] AS bid_top, asks[2][1] AS ask_top
  FROM g10_market_data
  WHERE instrument_id = '${INSTRUMENT}'
  LATEST ON timestamp PARTITION BY instrument_id
)
SELECT round((best_bid * ask_top + best_ask * bid_top) / (bid_top + ask_top), 5) AS "Microprice",
       round(best_ask - best_bid, 5) AS "Spread"
FROM snap;"""

# Imbalance needs every level's size on both sides -- but array_sum() does that with no
# UNNEST and no ladder expansion, reading only the size rows (bids[2]/asks[2]).
IMB_SQL = """SELECT round((array_sum(bids[2]) - array_sum(asks[2]))
                        / (array_sum(bids[2]) + array_sum(asks[2])), 4) AS "Imbalance"
FROM g10_market_data
WHERE instrument_id = '${INSTRUMENT}'
LATEST ON timestamp PARTITION BY instrument_id;"""

BLOTTER_SQL = """SELECT timestamp AS "Time", instrument_id AS "Instrument", ccy AS "Ccy",
  side AS "Side", notional AS "Notional", round(price, 4) AS "Price",
  client_id AS "Client", book AS "Book"
FROM g10_trades ORDER BY timestamp DESC LIMIT 25;"""


# --- Live board: LATEST ON / DESC LIMIT panels + MV-backed candles -> ticks at 100ms ---
live_panels = [
    plotly_panel(1, "Live ${CCY} Yield Curve", CURVE_SQL, CURVE_SCRIPT,
                 {"h": 7, "w": 24, "x": 0, "y": 0}),
    plotly_panel(2, "Market Depth - ${INSTRUMENT}", DEPTH_SQL, DEPTH_SCRIPT,
                 {"h": 11, "w": 12, "x": 0, "y": 7}),
    candle_panel(6, "${INSTRUMENT} price (1m candles, materialized view)", CANDLES_SQL,
                 {"h": 11, "w": 12, "x": 12, "y": 7}, time_from="1h"),
    stat_panel(10, "${CCY} curve slopes (bp)", SLOPE_SQL, {"h": 5, "w": 8, "x": 0, "y": 18}, decimals=1),
    stat_panel(11, "Microprice / spread - ${INSTRUMENT}", MICRO_SQL,
               {"h": 5, "w": 8, "x": 8, "y": 18}, decimals=4),
    gauge_panel(12, "Depth imbalance - ${INSTRUMENT}", IMB_SQL, {"h": 5, "w": 8, "x": 16, "y": 18}),
    table_panel(13, "Trade + hedge blotter (latest 25)", BLOTTER_SQL, {"h": 8, "w": 24, "x": 0, "y": 23}),
]

# --- Desk / risk board: the heavier analytical queries, slower refresh ---
desk_panels = [
    table_panel(20, "Joint Quote + Hedge (multi-way ASOF on dealt RFQs)", HERO_SQL,
                {"h": 10, "w": 24, "x": 0, "y": 0}),
    ts_panel(21, "Cumulative position (trading book) DV01 by tenor bucket - ${CCY}", RISK_TS_SQL,
             {"h": 9, "w": 12, "x": 0, "y": 10}),
    ts_panel(22, "${CCY} curve - key tenors (1m)", TENORS_SQL, {"h": 9, "w": 12, "x": 12, "y": 10}),
]

REFRESH_INTERVALS = ["100ms", "250ms", "500ms", "1s", "2s", "5s", "10s", "30s", "1m", "5m", "15m", "1h"]

INSTRUMENT_VAR = {
    "name": "INSTRUMENT", "type": "query", "label": "hedge instrument",
    "datasource": DS, "refresh": 1, "includeAll": False, "multi": False,
    "current": {"text": "USD_ZN", "value": "USD_ZN"},
    "query": "SELECT DISTINCT instrument_id FROM g10_instruments WHERE is_hedge = true ORDER BY instrument_id;",
    "definition": "SELECT DISTINCT instrument_id FROM g10_instruments WHERE is_hedge = true ORDER BY instrument_id;",
}

# Live board: CCY is derived from the chosen hedge instrument (hidden), so the single
# instrument dropdown drives the whole board -- pick EUR_FGBL and the curve, slopes and
# tenors all follow EUR. Grafana re-evaluates it whenever INSTRUMENT changes.
CCY_DERIVED_VAR = {
    "name": "CCY", "type": "query", "label": "ccy", "hide": 2,
    "datasource": DS, "refresh": 1, "includeAll": False, "multi": False,
    "query": "SELECT ccy FROM g10_instruments WHERE instrument_id = '${INSTRUMENT}' LIMIT 1;",
    "definition": "SELECT ccy FROM g10_instruments WHERE instrument_id = '${INSTRUMENT}' LIMIT 1;",
}

# Desk board has no order book, so it picks the currency directly.
CCY_PICK_VAR = {
    "name": "CCY", "type": "query", "label": "currency", "hide": 0,
    "datasource": DS, "refresh": 1, "includeAll": False, "multi": False,
    "current": {"text": "USD", "value": "USD"},
    "query": "SELECT DISTINCT ccy FROM g10_instruments ORDER BY ccy;",
    "definition": "SELECT DISTINCT ccy FROM g10_instruments ORDER BY ccy;",
}


def make_dashboard(title, uid, refresh, time_from, panels, variables, time_to="now"):
    return {
        "title": title, "uid": uid, "timezone": "browser", "schemaVersion": 39,
        "refresh": refresh, "time": {"from": time_from, "to": time_to},
        "timepicker": {"refresh_intervals": REFRESH_INTERVALS},
        "templating": {"list": variables},
        "tags": ["g10", "rates", "questdb"], "panels": panels,
    }


live = make_dashboard("G10 Rates - Live", "g10-rates-live", "100ms", "now-15m", live_panels,
                      [INSTRUMENT_VAR, CCY_DERIVED_VAR])
# Desk board defaults to yesterday's full session (EOD review; also the tiering story --
# older partitions served off cheaper storage). Day-scale, so its queries sample at 1m.
desk = make_dashboard("G10 Rates - Desk / Risk", "g10-rates-desk", "2s", "now-1d/d", desk_panels,
                      [CCY_PICK_VAR], time_to="now/d")


def write_and_deploy(dash, filename, refresh):
    # File: Grafana "export for sharing" form (a ${DS_QUESTDB} input) so a manual import
    # on any instance prompts for its own QuestDB datasource. The API payload binds the
    # resolved uid directly.
    portable = {
        "__inputs": [{
            "name": "DS_QUESTDB", "label": "QuestDB", "description": "",
            "type": "datasource", "pluginId": "questdb-questdb-datasource", "pluginName": "QuestDB",
        }],
        "__requires": [
            {"type": "datasource", "id": "questdb-questdb-datasource", "name": "QuestDB", "version": "1.0.0"},
            {"type": "panel", "id": "ae3e-plotly-panel", "name": "Plotly", "version": ""},
        ],
    }
    portable.update(json.loads(json.dumps(dash).replace(DS_UID, "${DS_QUESTDB}")))
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    with open(out_path, "w") as fh:
        json.dump(portable, fh, indent=2)
    print(f"wrote {out_path}")
    if os.environ.get("WRITE_ONLY"):
        return
    payload = {"dashboard": dash, "overwrite": True}
    req = urllib.request.Request(f"{GRAFANA_URL}/api/dashboards/db",
        data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    auth = base64.b64encode(f"{GRAFANA_USER}:{GRAFANA_PASS}".encode()).decode()
    req.add_header("Authorization", f"Basic {auth}")
    try:
        with urllib.request.urlopen(req) as resp:
            body = json.load(resp)
            print(f"deployed: {resp.status} {GRAFANA_URL}{body.get('url', '')}"
                  f"?refresh={refresh}&from={dash['time']['from']}&to={dash['time']['to']}")
    except urllib.error.HTTPError as e:
        print(f"ERROR {e.code}: {e.read().decode()}")
        sys.exit(1)


write_and_deploy(live, "g10_dashboard_live.json", "100ms")
write_and_deploy(desk, "g10_dashboard_desk.json", "2s")
if os.environ.get("WRITE_ONLY"):
    print("WRITE_ONLY set; skipped deploy.")
