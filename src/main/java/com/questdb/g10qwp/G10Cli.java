package com.questdb.g10qwp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line configuration for the G10 rates generator. Mirrors the FX/QWP
 * generator's flag surface and HA/auth model, with two pools:
 *
 * <ul>
 *   <li><b>market_data</b> — the only fan-out pool; depth snapshots on the liquid
 *       futures/govvies (the ~1B rows/day firehose). Snapshots are full-depth
 *       {@code DOUBLE[][]} arrays (1 row = 1 book), the fast QWP path.</li>
 *   <li><b>business</b> — a single writer for the low-volume tables that the hero
 *       query joins: {@code g10_core_price}, {@code g10_quotes}, {@code g10_trades},
 *       {@code g10_rfqs}, {@code g10_axes}. Single-writer per §13.4 (only the
 *       dominant table gets multiple WAL writers).</li>
 * </ul>
 *
 * Option names accept Python underscore or kebab form.
 */
public final class G10Cli {

    // --- connection / HA -----------------------------------------------------
    public List<String> hosts = new ArrayList<>(List.of("127.0.0.1:9000"));
    public boolean tls = false;
    public boolean tlsInsecure = false;
    public String token = null;
    public String user = null;
    public String password = null;
    public String sfDir = "/tmp/qwp_g10_sf";
    public String senderId = "qwp-g10-rates";
    public int autoFlushBytes = 524288;        // 512 KiB, safely under the ~1MB WS frame cap

    // --- mode / pools --------------------------------------------------------
    public String mode = null;                 // real-time | faster-than-life (required)
    public int marketDataProcesses = 2;        // fan-out pool for g10_market_data (0 = off)
    public int businessProcesses = 1;          // single writer for the business tables (0 or 1)

    // --- volume / time -------------------------------------------------------
    public int marketDataMinEps = 1200;        // depth snapshots/sec across the pool
    public int marketDataMaxEps = 15000;
    public int minLevels = 10;                 // book depth per snapshot (rates ~10)
    public int maxLevels = 10;
    public int coreMinEps = 800;               // g10_core_price snapshots/sec (curve mids + hedge BBO)
    public int coreMaxEps = 1400;
    public double quoteThresholdBp = 0.1;      // change-only: republish when mid moves > this (bp)
    public int quoteHeartbeatSecs = 30;        // ...or at least this often
    public double rfqsPerSec = 0.3;            // inbound RFQ arrival rate (bursty, low)
    public double dealRatio = 0.35;            // fraction of RFQs that deal -> trades (+ hedge)
    public double axeUpdateProbPerSec = 0.02;  // chance of an axe refresh each second

    public long totalMarketDataEvents = 1_000_000; // cap on the dominant table; 0 = unlimited
    public String startTs = null;
    public String endTs = null;
    public int runSecs = 0;                    // wall-clock stop (throughput tests)
    public int commitIntervalMs = 1000;
    public int marketDataCommitMs = 0;         // 0 = inherit commitIntervalMs
    public int businessCommitMs = 0;
    public int realtimeLookaheadSecs = 2;

    // --- reference data / schema --------------------------------------------
    public int yahooRefreshSecs = 300;
    public boolean noYahoo = false;
    public boolean shortTtl = false;
    public boolean enterprise = false;
    public boolean createViews = true;         // positions view + pos_risk MV + rollups (on by default)
    public String suffix = "";
    public int clientPoolSize = 500;

    public static G10Cli parse(String[] args) {
        G10Cli c = new G10Cli();
        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            String key = raw.startsWith("--") ? raw.substring(2).replace('-', '_')
                    : (raw.equals("-h") ? "help" : raw);
            switch (key) {
                case "hosts":
                case "host":
                    c.hosts = normalizeHosts(req(args, ++i, raw));
                    break;
                case "tls":
                    c.tls = true;
                    break;
                case "tls_insecure":
                    c.tls = true;
                    c.tlsInsecure = true;
                    break;
                case "token":
                    if (c.token != null) {
                        fail("use either --token or --token_file, not both");
                    }
                    c.token = req(args, ++i, raw);
                    break;
                case "token_file":
                    if (c.token != null) {
                        fail("use either --token or --token_file, not both");
                    }
                    c.token = readTokenFile(req(args, ++i, raw));
                    break;
                case "user":
                    c.user = req(args, ++i, raw);
                    break;
                case "password":
                    c.password = req(args, ++i, raw);
                    break;
                case "sf_dir":
                    c.sfDir = req(args, ++i, raw);
                    break;
                case "sender_id":
                    c.senderId = req(args, ++i, raw);
                    break;
                case "auto_flush_bytes":
                    c.autoFlushBytes = Integer.parseInt(req(args, ++i, raw));
                    break;

                case "mode":
                    c.mode = req(args, ++i, raw);
                    break;
                case "market_data_processes":
                    c.marketDataProcesses = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "business_processes":
                    c.businessProcesses = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "market_data_min_eps":
                    c.marketDataMinEps = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "market_data_max_eps":
                    c.marketDataMaxEps = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "min_levels":
                    c.minLevels = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "max_levels":
                    c.maxLevels = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "core_min_eps":
                    c.coreMinEps = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "core_max_eps":
                    c.coreMaxEps = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "quote_threshold_bp":
                    c.quoteThresholdBp = Double.parseDouble(req(args, ++i, raw));
                    break;
                case "quote_heartbeat_secs":
                    c.quoteHeartbeatSecs = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "rfqs_per_sec":
                    c.rfqsPerSec = Double.parseDouble(req(args, ++i, raw));
                    break;
                case "deal_ratio":
                    c.dealRatio = Double.parseDouble(req(args, ++i, raw));
                    break;
                case "axe_update_prob_per_sec":
                    c.axeUpdateProbPerSec = Double.parseDouble(req(args, ++i, raw));
                    break;
                case "total_market_data_events":
                case "total_events":
                    c.totalMarketDataEvents = Long.parseLong(req(args, ++i, raw));
                    break;
                case "start_ts":
                    c.startTs = req(args, ++i, raw);
                    break;
                case "end_ts":
                    c.endTs = req(args, ++i, raw);
                    break;
                case "run_secs":
                    c.runSecs = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "commit_interval_ms":
                    c.commitIntervalMs = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "market_data_commit_interval_ms":
                    c.marketDataCommitMs = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "business_commit_interval_ms":
                    c.businessCommitMs = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "realtime_lookahead_secs":
                    c.realtimeLookaheadSecs = Integer.parseInt(req(args, ++i, raw));
                    break;

                case "yahoo_refresh_secs":
                    c.yahooRefreshSecs = Integer.parseInt(req(args, ++i, raw));
                    break;
                case "no_yahoo":
                    c.noYahoo = true;
                    break;
                case "short_ttl":
                    i = boolFlag(args, i, v -> c.shortTtl = v);
                    break;
                case "enterprise":
                    i = boolFlag(args, i, v -> c.enterprise = v);
                    break;
                case "create_views":
                    i = boolFlag(args, i, v -> c.createViews = v);
                    break;
                case "suffix":
                    c.suffix = req(args, ++i, raw);
                    break;
                case "client_pool_size":
                    c.clientPoolSize = Integer.parseInt(req(args, ++i, raw));
                    break;

                case "help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    fail("Unknown argument: " + raw);
                    break;
            }
        }
        c.validate();
        return c;
    }

    private void validate() {
        if (!"real-time".equals(mode) && !"faster-than-life".equals(mode)) {
            fail("--mode must be 'real-time' or 'faster-than-life'");
        }
        if (hosts.isEmpty()) {
            fail("--hosts must list at least one host");
        }
        if (token != null && (user != null || password != null)) {
            fail("use either --token OR --user/--password, not both");
        }
        if ((user == null) != (password == null)) {
            fail("--user and --password must be provided together");
        }
        if (marketDataProcesses < 0 || marketDataProcesses > 30) {
            fail("--market_data_processes must be between 0 and 30");
        }
        if (businessProcesses < 0 || businessProcesses > 1) {
            fail("--business_processes must be 0 or 1 (single-writer by design)");
        }
        if (marketDataProcesses == 0 && businessProcesses == 0) {
            fail("enable at least one pool (--market_data_processes and/or --business_processes > 0)");
        }
        if (marketDataProcesses > 0) {
            if (marketDataMinEps <= 0 || marketDataMaxEps < marketDataMinEps) {
                fail("require 0 < --market_data_min_eps <= --market_data_max_eps");
            }
            if (minLevels < 1 || maxLevels < minLevels) {
                fail("require 1 <= --min_levels <= --max_levels");
            }
        }
        if (businessProcesses > 0 && (coreMinEps <= 0 || coreMaxEps < coreMinEps)) {
            fail("require 0 < --core_min_eps <= --core_max_eps");
        }
        if (autoFlushBytes < 1024) {
            fail("--auto_flush_bytes must be >= 1024");
        }
        if (runSecs < 0) {
            fail("--run_secs must be >= 0");
        }
        if (commitIntervalMs < 1) {
            fail("--commit_interval_ms must be >= 1");
        }
        if (realtimeLookaheadSecs < 0) {
            fail("--realtime_lookahead_secs must be >= 0");
        }
        if (marketDataCommitMs < 0 || businessCommitMs < 0) {
            fail("per-pool commit intervals must be >= 0 (0 = inherit --commit_interval_ms)");
        }
        if ("faster-than-life".equals(mode) && totalMarketDataEvents <= 0 && endTs == null && runSecs <= 0) {
            fail("faster-than-life requires a bound: set --total_market_data_events > 0, --end_ts, or --run_secs");
        }
    }

    public String instrumentsTable() {
        return "g10_instruments" + suffix;
    }

    public String marketDataTable() {
        return "g10_market_data" + suffix;
    }

    public String corePriceTable() {
        return "g10_core_price" + suffix;
    }

    public String quotesTable() {
        return "g10_quotes" + suffix;
    }

    public String tradesTable() {
        return "g10_trades" + suffix;
    }

    public String rfqsTable() {
        return "g10_rfqs" + suffix;
    }

    public String axesTable() {
        return "g10_axes" + suffix;
    }

    public int marketDataCommitIntervalMs() {
        return marketDataCommitMs > 0 ? marketDataCommitMs : commitIntervalMs;
    }

    public int businessCommitIntervalMs() {
        return businessCommitMs > 0 ? businessCommitMs : commitIntervalMs;
    }

    public String scheme() {
        return tls ? "wss" : "ws";
    }

    public String queryClientConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme()).append("::addr=").append(String.join(",", hosts)).append(';');
        if (token != null) {
            sb.append("token=").append(token).append(';');
        } else if (user != null) {
            sb.append("username=").append(user).append(';').append("password=").append(password).append(';');
        }
        if (tls && tlsInsecure) {
            sb.append("tls_verify=unsafe_off;");
        }
        if (hosts.size() > 1) {
            sb.append("failover=on;");
        }
        return sb.toString();
    }

    // ---- arg helpers --------------------------------------------------------

    private static int boolFlag(String[] args, int i, java.util.function.Consumer<Boolean> set) {
        if (i + 1 < args.length) {
            String v = args[i + 1].toLowerCase();
            if (v.equals("true")) {
                set.accept(Boolean.TRUE);
                return i + 1;
            }
            if (v.equals("false")) {
                set.accept(Boolean.FALSE);
                return i + 1;
            }
        }
        set.accept(Boolean.TRUE);
        return i;
    }

    private static List<String> normalizeHosts(String csv) {
        List<String> out = new ArrayList<>();
        for (String h : csv.split(",")) {
            String t = h.trim();
            if (!t.isEmpty()) {
                out.add(t.contains(":") ? t : t + ":9000");
            }
        }
        return out;
    }

    private static String req(String[] args, int i, String flag) {
        if (i >= args.length) {
            fail("missing value for " + flag);
        }
        return args[i];
    }

    private static String readTokenFile(String path) {
        try {
            String t = Files.readString(Paths.get(path)).trim();
            if (t.isEmpty()) {
                fail("--token_file " + path + " is empty");
            }
            return t;
        } catch (IOException e) {
            fail("could not read --token_file " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static void fail(String msg) {
        System.err.println("ERROR: " + msg + "\n");
        printUsage();
        throw new IllegalArgumentException(msg);
    }

    public static void printUsage() {
        System.out.println(String.join("\n", Arrays.asList(
                "qwp-g10-rates — synthetic G10 interest-rate generator over QuestDB QWP (WebSocket), HA-aware.",
                "Mirrors the FX/QWP generator: a deterministic per-currency curve walk feeds depth, mids,",
                "quotes, trades and bump-reprice DV01, all coherent for the multi-way ASOF hero query.",
                "",
                "Required:  --mode <real-time|faster-than-life>",
                "",
                "Connection (shared across all hosts):",
                "  --hosts h1:9000,h2:9000   HA fleet (default 127.0.0.1:9000)   --host <h:port> single-host alias",
                "  --tls | --tls_insecure    wss (insecure also skips cert validation)",
                "  --token <t> | --token_file <path> | --user <u> --password <p>",
                "  --sf_dir <dir>            store-and-forward dir (default /tmp/qwp_g10_sf)",
                "  --auto_flush_bytes <n>    QWP auto-flush size (default 524288 = 512 KiB)",
                "",
                "Pools:",
                "  --market_data_processes <n>   depth firehose workers, 0-30 (default 2; 0 = off)",
                "  --business_processes <n>      business-tables writer, 0 or 1 (default 1)",
                "",
                "Volume / time:",
                "  --market_data_min_eps / --market_data_max_eps   depth snapshots/sec (default 1200/15000)",
                "  --min_levels / --max_levels                     book depth per snapshot (default 10/10)",
                "  --core_min_eps / --core_max_eps                 g10_core_price snapshots/sec (default 800/1400)",
                "  --quote_threshold_bp <x>     change-only republish threshold in bp (default 0.1)",
                "  --quote_heartbeat_secs <n>   quote heartbeat (default 30)",
                "  --rfqs_per_sec <x>           inbound RFQ rate (default 0.3)",
                "  --deal_ratio <x>             fraction of RFQs that deal -> trades (default 0.35)",
                "  --total_market_data_events <n>  cap on the dominant table; stops the run; 0 = unlimited",
                "  --start_ts / --end_ts <iso>  faster-than-life data window",
                "  --run_secs <n>               wall-clock stop (throughput tests)",
                "  --commit_interval_ms <n>     commit cadence (default 1000); per-pool overrides available",
                "  --realtime_lookahead_secs <n>  real-time: stamp events n s ahead of wall-clock (default 2)",
                "",
                "Reference data / schema:",
                "  --no_yahoo                 skip the live US-anchor sanity fetch (deterministic curve regardless)",
                "  --short_ttl [true|false]   attach retention",
                "  --enterprise [true|false]  with --short_ttl, use STORAGE POLICY instead of TTL",
                "  --create_views [true|false]  positions view + pos_risk MV + rollups (default true)",
                "  --suffix <s>               append suffix to every table name",
                "  --client_pool_size <n>     distinct clients (default 500)")));
    }
}
