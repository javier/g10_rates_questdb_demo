#!/usr/bin/env python3
"""Build and deploy the G10 Rates Grafana dashboard.

Mirrors the FX "Orderbook Realtime" dashboard but tells the rates story:
a live yield curve, depth on a hedge instrument, the joint quote+hedge join,
the curve ticking, and current book risk by bucket.

Usage:
    python3 grafana/deploy_dashboard.py
Env overrides: GRAFANA_URL, GRAFANA_USER, GRAFANA_PASS, QDB_DS_UID.
"""
import json
import os
import sys
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
  WHERE $__timeFilter(timestamp) AND instrument_id = '${INSTRUMENT}'
  ORDER BY timestamp DESC LIMIT 1
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
WHERE i.price_space = 'rate' AND i.ccy = 'USD'
ORDER BY i.tenor_years;"""

TENORS_SQL = """SELECT timestamp AS time,
  last(CASE WHEN instrument_id = 'USD_OIS_2Y'  THEN mid END) AS "USD 2Y",
  last(CASE WHEN instrument_id = 'USD_OIS_5Y'  THEN mid END) AS "USD 5Y",
  last(CASE WHEN instrument_id = 'USD_OIS_10Y' THEN mid END) AS "USD 10Y",
  last(CASE WHEN instrument_id = 'USD_OIS_30Y' THEN mid END) AS "USD 30Y"
FROM g10_core_price
WHERE $__timeFilter(timestamp)
  AND instrument_id IN ('USD_OIS_2Y','USD_OIS_5Y','USD_OIS_10Y','USD_OIS_30Y')
SAMPLE BY 1s;"""

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
CANDLES_SQL = """SELECT timestamp AS time, open, high, low, close
FROM g10_curve_mid_1m
WHERE $__timeFilter(timestamp) AND instrument_id = 'USD_OIS_10Y';"""

# Cumulative book DV01 by bucket over the visible window, pivoted to one column per
# bucket so each draws as its own line (off the g10_pos_risk MV).
RISK_TS_SQL = """WITH f AS (
  SELECT timestamp, tenor_bucket, sum(dv01_flow) AS dv01
  FROM g10_pos_risk WHERE $__timeFilter(timestamp) AND ccy = 'USD' SAMPLE BY 30s
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
FROM c SAMPLE BY 30s;"""

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


panels = [
    plotly_panel(1, "Live USD Yield Curve", CURVE_SQL, CURVE_SCRIPT,
                 {"h": 8, "w": 24, "x": 0, "y": 0}),
    plotly_panel(2, "Market Depth - ${INSTRUMENT}", DEPTH_SQL, DEPTH_SCRIPT,
                 {"h": 10, "w": 12, "x": 0, "y": 8}),
    candle_panel(6, "USD 10Y swap rate (1m candles, materialized view)", CANDLES_SQL,
                 {"h": 10, "w": 12, "x": 12, "y": 8}, time_from="1h"),
    table_panel(3, "Joint Quote + Hedge (multi-way ASOF on dealt RFQs)", HERO_SQL,
                {"h": 9, "w": 12, "x": 0, "y": 18}),
    ts_panel(7, "Cumulative book DV01 by bucket - USD (over window)", RISK_TS_SQL,
             {"h": 9, "w": 12, "x": 12, "y": 18}),
    ts_panel(4, "USD curve - key tenors (1s)", TENORS_SQL, {"h": 7, "w": 24, "x": 0, "y": 27}),
]

dashboard = {
    "title": "G10 Rates - Realtime Demo",
    "uid": "g10-rates-realtime",
    "timezone": "browser",
    "schemaVersion": 39,
    "refresh": "250ms",
    "time": {"from": "now-15m", "to": "now"},
    "timepicker": {"refresh_intervals": ["250ms", "500ms", "1s", "2s", "5s", "10s", "30s", "1m", "5m", "15m", "1h"]},
    "templating": {"list": [{
        "name": "INSTRUMENT", "type": "query", "label": "hedge instrument",
        "datasource": DS, "refresh": 2, "includeAll": False, "multi": False,
        "current": {"text": "USD_ZN", "value": "USD_ZN"},
        "query": "SELECT DISTINCT instrument_id FROM g10_instruments WHERE is_hedge = true ORDER BY instrument_id;",
        "definition": "SELECT DISTINCT instrument_id FROM g10_instruments WHERE is_hedge = true ORDER BY instrument_id;",
    }]},
    "tags": ["g10", "rates", "questdb"],
    "panels": panels,
}

payload = {"dashboard": dashboard, "overwrite": True}

# Save the JSON to the repo for version control / manual import.
out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "g10_dashboard.json")
with open(out_path, "w") as fh:
    json.dump(dashboard, fh, indent=2)
print(f"wrote {out_path}")

req = urllib.request.Request(
    f"{GRAFANA_URL}/api/dashboards/db",
    data=json.dumps(payload).encode(),
    headers={"Content-Type": "application/json"},
)
import base64
auth = base64.b64encode(f"{GRAFANA_USER}:{GRAFANA_PASS}".encode()).decode()
req.add_header("Authorization", f"Basic {auth}")
try:
    with urllib.request.urlopen(req) as resp:
        body = json.load(resp)
        url = f"{GRAFANA_URL}{body.get('url', '')}?refresh=250ms&from=now-15m&to=now"
        print(f"deployed: {resp.status} {url}")
except urllib.error.HTTPError as e:
    print(f"ERROR {e.code}: {e.read().decode()}")
    sys.exit(1)
