package com.questdb.g10qwp;

import io.questdb.client.Sender;
import io.questdb.client.cutlass.line.array.DoubleArray;
import io.questdb.client.cutlass.qwp.client.QwpColumnBatch;
import io.questdb.client.cutlass.qwp.client.QwpColumnBatchHandler;
import io.questdb.client.cutlass.qwp.client.QwpQueryClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic G10 interest-rate generator over QuestDB QWP (WebSocket), HA-aware — the
 * rates sibling of the FX/QWP generator.
 *
 * <p>Two pools (§13.4): a fan-out <b>market_data</b> pool that streams full-depth book
 * snapshots ({@code DOUBLE[][]}) on the liquid futures/govvies — the ~1B-rows/day
 * firehose — and a single-writer <b>business</b> pool that emits the low-volume tables
 * the hero query joins ({@code core_price}, {@code quotes}, {@code trades}, {@code rfqs},
 * {@code axes}). Both pools drive their own {@link CurveEngine}; because the curve is
 * deterministic in {@code (ccy, second)}, every table is coherent by construction and
 * the multi-way ASOF join produces sane skews and hedges without any shared state.
 */
public final class G10Generator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long NANOS_PER_SEC = 1_000_000_000L;
    private static final String ENTERPRISE_POLICY =
            "TO REMOTE 1 hour, TO PARQUET 2 days, DROP LOCAL 3 months";
    // Fixed load timestamp for the static dimension so re-seeds DEDUP to one row/instrument.
    private static final long INSTRUMENTS_LOAD_NS = 946_684_800L * NANOS_PER_SEC; // 2000-01-01

    private enum Kind { MARKET_DATA, BUSINESS }

    private final G10Cli cfg;
    private final List<Instrument> instruments = G10Universe.instruments();
    private final Map<String, Integer> idx = new HashMap<>();
    private final int[] hedgeIdx;                 // indices of depth (hedge) instruments
    private final int[] quotableIdx;              // instruments we stream quotes on
    private final int[][] futuresByCcy;           // ccy -> future instrument indices (hedge legs)
    private final String[] clients;
    private final long[] ladder = G10Universe.makeVolumeLadder();

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean pausedMd = new AtomicBoolean(false);
    private final AtomicBoolean pausedBiz = new AtomicBoolean(false);
    private final AtomicLong mdRows = new AtomicLong(0);
    private final AtomicLong coreRows = new AtomicLong(0);
    private final AtomicLong quoteRows = new AtomicLong(0);
    private final AtomicLong tradeRows = new AtomicLong(0);
    private final AtomicLong rfqRows = new AtomicLong(0);
    private final AtomicLong axeRows = new AtomicLong(0);
    private final AtomicLong mdFinishMs = new AtomicLong(0);
    private final AtomicLong bizFinishMs = new AtomicLong(0);

    private G10Generator(G10Cli cfg) {
        this.cfg = cfg;
        for (int i = 0; i < instruments.size(); i++) {
            idx.put(instruments.get(i).id, i);
        }
        List<Integer> hedge = new ArrayList<>();
        List<Integer> quotable = new ArrayList<>();
        Map<Integer, List<Integer>> futs = new HashMap<>();
        for (int i = 0; i < instruments.size(); i++) {
            Instrument in = instruments.get(i);
            if (in.hedge) {
                hedge.add(i);
            }
            boolean streamSwap = in.isRate() && (in.tenorYears >= 2.0);
            if (streamSwap || "FUTURE".equals(in.product)) {
                quotable.add(i);
            }
            if ("FUTURE".equals(in.product)) {
                futs.computeIfAbsent(in.ccyIndex, k -> new ArrayList<>()).add(i);
            }
        }
        this.hedgeIdx = hedge.stream().mapToInt(Integer::intValue).toArray();
        this.quotableIdx = quotable.stream().mapToInt(Integer::intValue).toArray();
        this.futuresByCcy = new int[G10Universe.CCYS.length][];
        for (int c = 0; c < futuresByCcy.length; c++) {
            List<Integer> l = futs.getOrDefault(c, List.of());
            futuresByCcy[c] = l.stream().mapToInt(Integer::intValue).toArray();
        }
        this.clients = G10Universe.clientPool(cfg.clientPoolSize);
    }

    public static void main(String[] args) {
        G10Cli cfg;
        try {
            cfg = G10Cli.parse(args);
        } catch (IllegalArgumentException e) {
            System.exit(2);
            return;
        }
        try {
            new G10Generator(cfg).run();
        } catch (Exception e) {
            System.err.printf("[FATAL] %s: %s%n", e.getClass().getSimpleName(), e.getMessage());
            System.exit(1);
        }
    }

    private void run() throws Exception {
        System.out.println("=== qwp-g10-rates generator ===");
        System.out.printf("mode=%s  hosts=%s  tls=%s  market_data_processes=%d  business_processes=%d%n",
                cfg.mode, cfg.hosts, cfg.tls, cfg.marketDataProcesses, cfg.businessProcesses);
        System.out.printf("universe: %d instruments (%d hedge/depth, %d quotable)%n",
                instruments.size(), hedgeIdx.length, quotableIdx.length);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false)));

        // 1) Seed the curve's DV01 onto each instrument (bump-reprice off the seed curve)
        //    so the seed dv01_per_unit == the analytic risk number (§14.4).
        new CurveEngine().seedDv01(instruments);
        if (!cfg.noYahoo) {
            new YahooRates().logUsAnchor();
        }

        // 2) Schema: tables, the seeded static dimension, then views/matviews.
        createTables();
        seedInstruments();
        createViews();

        // 3) Data-clock start with the same overlap safety as the FX generator.
        Long endNs = cfg.endTs == null ? null : isoToNanos(cfg.endTs);
        long startNs = resolveStartNanos(endNs);

        long wallStartMs = System.currentTimeMillis();
        long t0 = System.nanoTime();

        List<Long> perSecond = new ArrayList<>();
        Thread sampler = cfg.runSecs > 0 ? startThroughputSampler(perSecond) : null;
        Thread deadline = cfg.runSecs > 0 ? startDeadline(cfg.runSecs) : null;
        Thread heartbeat = cfg.runSecs == 0 ? startHeartbeat() : null;
        Thread walMonitor = startWalMonitor();

        List<Thread> workers = new ArrayList<>();
        workers.addAll(spawnMarketData(startNs, endNs, wallStartMs));
        workers.addAll(spawnBusiness(startNs, endNs, wallStartMs));
        for (Thread th : workers) {
            th.join();
        }

        running.set(false);
        if (deadline != null) {
            deadline.interrupt();
            deadline.join(1000);
        }
        if (sampler != null) {
            sampler.interrupt();
            sampler.join(1500);
        }
        if (heartbeat != null) {
            heartbeat.interrupt();
            heartbeat.join(1000);
        }
        if (walMonitor != null) {
            walMonitor.join(2000);
        }

        double secs = (System.nanoTime() - t0) / 1e9;
        printThroughputSummary(perSecond);
        long total = mdRows.get() + coreRows.get() + quoteRows.get() + tradeRows.get()
                + rfqRows.get() + axeRows.get();
        System.out.printf("[DONE] in %.2fs wall — %,d rows total:%n", secs, total);
        printPoolDone("market_data", mdRows.get(), mdFinishMs.get(), wallStartMs);
        System.out.printf("  business: core=%,d quotes=%,d trades=%,d rfqs=%,d axes=%,d%n",
                coreRows.get(), quoteRows.get(), tradeRows.get(), rfqRows.get(), axeRows.get());
    }

    // ---------------------------------------------------------------- pools

    private List<Thread> spawnMarketData(long startNs, Long endNs, long wallStartMs) {
        List<Thread> threads = new ArrayList<>();
        if (cfg.marketDataProcesses <= 0 || hedgeIdx.length == 0) {
            return threads;
        }
        int p = cfg.marketDataProcesses;
        List<List<Integer>> assign = snakeDraft(hedgeIdx, p);
        for (int w = 0; w < p; w++) {
            Worker worker = new Worker(Kind.MARKET_DATA, w, assign.get(w), startNs, endNs, wallStartMs);
            Thread th = new Thread(worker, "qwp-md-" + w);
            threads.add(th);
            th.start();
        }
        return threads;
    }

    private List<Thread> spawnBusiness(long startNs, Long endNs, long wallStartMs) {
        List<Thread> threads = new ArrayList<>();
        if (cfg.businessProcesses <= 0) {
            return threads;
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < instruments.size(); i++) {
            all.add(i);
        }
        Worker worker = new Worker(Kind.BUSINESS, 0, all, startNs, endNs, wallStartMs);
        Thread th = new Thread(worker, "qwp-biz-0");
        threads.add(th);
        th.start();
        return threads;
    }

    /** Snake-draft a set of instrument indices across {@code p} workers (balanced). */
    private static List<List<Integer>> snakeDraft(int[] items, int p) {
        List<List<Integer>> res = new ArrayList<>();
        for (int w = 0; w < p; w++) {
            res.add(new ArrayList<>());
        }
        int k = 0;
        for (int item : items) {
            res.get(k % p).add(item);
            k++;
        }
        return res;
    }

    // ---------------------------------------------------------------- worker

    private final class Worker implements Runnable {
        private final Kind kind;
        private final int id;
        private final int[] owned;
        private final CurveEngine curve = new CurveEngine();
        private final long startNs;
        private final Long endNs;
        private final long wallStartMs;
        private final long tsLookaheadNs;
        private final Map<Integer, DoubleArray> bidArrays = new HashMap<>();
        private final Map<Integer, DoubleArray> askArrays = new HashMap<>();
        // Business-only per-instrument state (sized to the full universe).
        private final double[] lastQuotedMid;
        private final long[] lastQuoteSec;
        private final double[] inventory;
        private boolean stop = false;

        Worker(Kind kind, int id, List<Integer> ownedList, long startNs, Long endNs, long wallStartMs) {
            this.kind = kind;
            this.id = id;
            this.owned = ownedList.stream().mapToInt(Integer::intValue).toArray();
            this.startNs = startNs;
            this.endNs = endNs;
            this.wallStartMs = wallStartMs;
            this.tsLookaheadNs = "real-time".equals(cfg.mode)
                    ? (long) cfg.realtimeLookaheadSecs * NANOS_PER_SEC : 0L;
            int n = instruments.size();
            this.lastQuotedMid = new double[n];
            this.lastQuoteSec = new long[n];
            this.inventory = new double[n];
            Arrays.fill(lastQuotedMid, Double.NaN);
            Arrays.fill(lastQuoteSec, Long.MIN_VALUE / 2);
        }

        @Override
        public void run() {
            int commitMs = kind == Kind.MARKET_DATA
                    ? cfg.marketDataCommitIntervalMs() : cfg.businessCommitIntervalMs();
            boolean realtime = "real-time".equals(cfg.mode);
            try (Sender sender = buildSender(kind, id)) {
                long base = alignToSecond(startNs);
                long secondStartNs = base;
                if (realtime) {
                    long sliceNs = Math.min(NANOS_PER_SEC, Math.max(1L, (long) commitMs) * 1_000_000L);
                    while (running.get() && !stop && !capReached() && !pastEnd(secondStartNs)) {
                        waitWhilePaused();
                        if (!running.get()) {
                            break;
                        }
                        long k = (secondStartNs - base) / NANOS_PER_SEC;
                        emitSecond(sender, secondStartNs, k);
                        sender.flush();
                        long deadlineMs = wallStartMs + (k + 1) * 1000L;
                        long sleepMs = deadlineMs - System.currentTimeMillis();
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                        secondStartNs += NANOS_PER_SEC;
                    }
                } else {
                    long lastCommitMs = System.currentTimeMillis();
                    while (running.get() && !stop && !capReached() && !pastEnd(secondStartNs)) {
                        waitWhilePaused();
                        if (!running.get()) {
                            break;
                        }
                        long k = (secondStartNs - base) / NANOS_PER_SEC;
                        emitSecond(sender, secondStartNs, k);
                        secondStartNs += NANOS_PER_SEC;
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastCommitMs >= commitMs) {
                            sender.flush();
                            lastCommitMs = nowMs;
                        }
                    }
                }
                sender.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.printf("[%s worker %d] FATAL: %s%n", tag(kind), id, e.getMessage());
            } finally {
                (kind == Kind.MARKET_DATA ? mdFinishMs : bizFinishMs)
                        .accumulateAndGet(System.currentTimeMillis(), Math::max);
                bidArrays.values().forEach(DoubleArray::close);
                askArrays.values().forEach(DoubleArray::close);
            }
        }

        /** Advance the shared curve for second {@code k}, then emit this worker's tables. */
        private void emitSecond(Sender sender, long secondStartNs, long k) {
            curve.beginSecond(k);
            if (kind == Kind.MARKET_DATA) {
                emitDepth(sender, secondStartNs, k);
            } else {
                emitCore(sender, secondStartNs);
                emitQuotes(sender, secondStartNs, k);
                emitBusinessFlow(sender, secondStartNs);
            }
            curve.endSecond();
        }

        // --- market_data: full-depth book snapshots on the hedge instruments --------

        private void emitDepth(Sender sender, long secondStartNs, long k) {
            double share = (double) owned.length / Math.max(1, hedgeIdx.length);
            int wMin = Math.max(1, (int) Math.round(cfg.marketDataMinEps * share));
            int wMax = Math.max(wMin, (int) Math.round(cfg.marketDataMaxEps * share));
            int nEvents = ThreadLocalRandom.current().nextInt(wMin, wMax + 1);
            long[] offsets = sortedOffsets(nEvents);
            for (long off : offsets) {
                if (capReached()) {
                    stop = true;
                    return;
                }
                long ts = secondStartNs + off + tsLookaheadNs;
                if (endNs != null && ts >= endNs) {
                    stop = true;
                    return;
                }
                int gi = owned[ThreadLocalRandom.current().nextInt(owned.length)];
                Instrument in = instruments.get(gi);
                double[] betas = curve.betasAt(in.ccyIndex, (double) off / NANOS_PER_SEC);
                double mid = G10Universe.quantizeToTick(curve.mid(in, betas), in.tickSize);
                emitSnapshot(sender, ts, in, mid);
                mdRows.incrementAndGet();
            }
        }

        private void emitSnapshot(Sender sender, long ts, Instrument in, double mid) {
            int levels = ThreadLocalRandom.current().nextInt(cfg.minLevels, cfg.maxLevels + 1);
            double half = halfSpread(in);
            double bestBid = G10Universe.quantizeToTick(mid - half, in.tickSize);
            double bestAsk = G10Universe.quantizeToTick(mid + half, in.tickSize);
            DoubleArray bids = bidArrays.computeIfAbsent(levels, L -> new DoubleArray(2, L));
            DoubleArray asks = askArrays.computeIfAbsent(levels, L -> new DoubleArray(2, L));
            bids.clear();
            asks.clear();
            for (int i = 0; i < levels; i++) {
                bids.append(G10Universe.quantizeToTick(bestBid - i * in.tickSize, in.tickSize));
            }
            for (int i = 0; i < levels; i++) {
                asks.append(G10Universe.quantizeToTick(bestAsk + i * in.tickSize, in.tickSize));
            }
            for (int i = 0; i < levels; i++) {
                bids.append((double) G10Universe.volumeForLevel(i, ladder));
            }
            for (int i = 0; i < levels; i++) {
                asks.append((double) G10Universe.volumeForLevel(i, ladder));
            }
            sender.table(cfg.marketDataTable())
                    .symbol("instrument_id", in.id)
                    .symbol("venue", venueFor(in))
                    .symbol("basis", "price")
                    .doubleArray("bids", bids)
                    .doubleArray("asks", asks)
                    .doubleColumn("best_bid", bestBid)
                    .doubleColumn("best_ask", bestAsk)
                    .at(ts, ChronoUnit.NANOS);
        }

        // --- core_price: consolidated BBO / curve mids on every instrument ----------

        private void emitCore(Sender sender, long secondStartNs) {
            int nEvents = ThreadLocalRandom.current().nextInt(cfg.coreMinEps, cfg.coreMaxEps + 1);
            long[] offsets = sortedOffsets(nEvents);
            for (long off : offsets) {
                if (capReached()) {
                    stop = true;
                    return;
                }
                long ts = secondStartNs + off + tsLookaheadNs;
                if (endNs != null && ts >= endNs) {
                    stop = true;
                    return;
                }
                Instrument in = instruments.get(owned[ThreadLocalRandom.current().nextInt(owned.length)]);
                double[] betas = curve.betasAt(in.ccyIndex, (double) off / NANOS_PER_SEC);
                double mid = priceQuantized(in, betas);
                double half = halfSpread(in);
                double bid = roundIf(in, mid - half);
                double ask = roundIf(in, mid + half);
                long sz = G10Universe.volumeForLevel(0, ladder);
                sender.table(cfg.corePriceTable())
                        .symbol("instrument_id", in.id)
                        .symbol("ccy", in.ccy)
                        .symbol("basis", in.priceSpace)
                        .doubleColumn("bid", bid)
                        .doubleColumn("ask", ask)
                        .doubleColumn("mid", mid)
                        .doubleColumn("bid_sz", (double) sz)
                        .doubleColumn("ask_sz", (double) sz)
                        .at(ts, ChronoUnit.NANOS);
                coreRows.incrementAndGet();
            }
        }

        // --- quotes: change-only streaming, skewed by axe + inventory ---------------

        private void emitQuotes(Sender sender, long secondStartNs, long k) {
            int slot = 0;
            for (int gi : quotableIdx) {
                Instrument in = instruments.get(gi);
                double[] betas = curve.closeBetas(in.ccyIndex);
                double mid = priceQuantized(in, betas);
                double prev = lastQuotedMid[gi];
                double moveBp = Double.isNaN(prev) ? Double.MAX_VALUE : bpMove(in, mid, prev);
                boolean heartbeat = (k - lastQuoteSec[gi]) >= cfg.quoteHeartbeatSecs;
                if (moveBp <= cfg.quoteThresholdBp && !heartbeat) {
                    continue;   // change-only: nothing moved enough, no heartbeat due
                }
                lastQuotedMid[gi] = mid;
                lastQuoteSec[gi] = k;
                double skewBps = inventorySkewBps(gi);
                double half = halfSpread(in);
                long off = Math.min(NANOS_PER_SEC - 1, (long) slot++ * 137L);  // ascending within the second
                long ts = secondStartNs + off + tsLookaheadNs;
                if (endNs != null && ts >= endNs) {
                    return;
                }
                long validUntilMicros = (ts + 5 * NANOS_PER_SEC) / 1000L;
                for (String platform : G10Universe.PLATFORMS) {
                    double skewPx = skewInPriceUnits(in, skewBps);
                    sender.table(cfg.quotesTable())
                            .symbol("instrument_id", in.id)
                            .symbol("ccy", in.ccy)
                            .symbol("platform", platform)
                            .doubleColumn("bid", roundIf(in, mid - half + skewPx))
                            .doubleColumn("ask", roundIf(in, mid + half + skewPx))
                            .doubleColumn("mid", mid)
                            .doubleColumn("skew_bps", skewBps)
                            .timestampColumn("valid_until", validUntilMicros, ChronoUnit.MICROS)
                            .uuidColumn("quote_id", ThreadLocalRandom.current().nextLong(),
                                    ThreadLocalRandom.current().nextLong())
                            .at(ts, ChronoUnit.NANOS);
                    quoteRows.incrementAndGet();
                }
            }
        }

        // --- rfqs (+ the trades and axes they drive) --------------------------------

        private void emitBusinessFlow(Sender sender, long secondStartNs) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            // Inbound RFQs: low, bursty. Draw a small count around the per-second rate.
            int nRfq = (int) Math.floor(cfg.rfqsPerSec)
                    + (rnd.nextDouble() < (cfg.rfqsPerSec - Math.floor(cfg.rfqsPerSec)) ? 1 : 0);
            long tradeOff = 1;
            for (int r = 0; r < nRfq; r++) {
                long off = rnd.nextLong(NANOS_PER_SEC);
                long ts = secondStartNs + off + tsLookaheadNs;
                if (endNs != null && ts >= endNs) {
                    return;
                }
                // RFQ lands on a swap tenor (clients trade the curve, hedge in futures).
                int gi = quotableSwap(rnd);
                Instrument in = instruments.get(gi);
                boolean clientBuy = rnd.nextBoolean();
                double notional = G10Universe.rfqNotional(in.tenorYears);
                String client = clients[rnd.nextInt(clients.length)];
                String platform = G10Universe.PLATFORMS[rnd.nextInt(G10Universe.PLATFORMS.length)];
                boolean deals = rnd.nextDouble() < cfg.dealRatio;
                String status = deals ? "dealt" : (rnd.nextBoolean() ? "quoted" : "passed");
                String rfqId = "RQ_" + Long.toHexString(ts) + "_" + r;
                sender.table(cfg.rfqsTable())
                        .symbol("rfq_id", rfqId)
                        .symbol("client_id", client)
                        .symbol("platform", platform)
                        .symbol("ccy", in.ccy)
                        .symbol("tenor", in.tenor)
                        .symbol("instrument_id", in.id)
                        .symbol("side", clientBuy ? "buy" : "sell")
                        .doubleColumn("notional", notional)
                        .symbol("status", status)
                        .at(ts, ChronoUnit.NANOS);
                rfqRows.incrementAndGet();

                // Our RFQ response: a quote with a NON-NULL rfq_id, which is what
                // distinguishes it from the streaming firehose (null rfq_id, §5.1).
                double[] qBetas = curve.betasAt(in.ccyIndex, (double) off / NANOS_PER_SEC);
                double qMid = priceQuantized(in, qBetas);
                double qHalf = halfSpread(in);
                double qSkewBps = inventorySkewBps(gi);
                double qSkewPx = skewInPriceUnits(in, qSkewBps);
                sender.table(cfg.quotesTable())
                        .symbol("rfq_id", rfqId)
                        .symbol("instrument_id", in.id)
                        .symbol("ccy", in.ccy)
                        .symbol("platform", platform)
                        .doubleColumn("bid", roundIf(in, qMid - qHalf + qSkewPx))
                        .doubleColumn("ask", roundIf(in, qMid + qHalf + qSkewPx))
                        .doubleColumn("mid", qMid)
                        .doubleColumn("skew_bps", qSkewBps)
                        .timestampColumn("valid_until", (ts + 30 * NANOS_PER_SEC) / 1000L, ChronoUnit.MICROS)
                        .uuidColumn("quote_id", rnd.nextLong(), rnd.nextLong())
                        .at(ts + 1, ChronoUnit.NANOS);
                quoteRows.incrementAndGet();

                if (deals) {
                    double[] betas = curve.betasAt(in.ccyIndex, (double) off / NANOS_PER_SEC);
                    double mid = priceQuantized(in, betas);
                    double half = halfSpread(in);
                    // Client lifts our offer / hits our bid; we take the opposite sign.
                    double dealPx = roundIf(in, clientBuy ? mid + half : mid - half);
                    double signedClient = clientBuy ? -notional : notional;  // our position vs client
                    inventory[gi] += signedClient;
                    emitTrade(sender, secondStartNs + off + tradeOff++, in, rfqId,
                            clientBuy ? "sell" : "buy", notional, dealPx, client,
                            G10Universe.BOOKS[in.ccyIndex], false);
                    // Implied hedge: offset DV01 in the nearest liquid future.
                    int hf = nearestFuture(in.ccyIndex, in.tenorYears);
                    if (hf >= 0) {
                        Instrument fut = instruments.get(hf);
                        double[] fb = curve.betasAt(fut.ccyIndex, (double) off / NANOS_PER_SEC);
                        double futPx = priceQuantized(fut, fb);
                        double hedgeNotional = Math.abs(signedClient) * in.dv01PerUnit
                                / Math.max(1e-12, fut.dv01PerUnit);
                        hedgeNotional = Math.round(hedgeNotional / 1_000_000.0) * 1_000_000.0;
                        boolean hedgeBuy = signedClient < 0;  // short risk -> buy futures to flatten
                        inventory[hf] += hedgeBuy ? hedgeNotional : -hedgeNotional;
                        emitTrade(sender, secondStartNs + off + tradeOff++, fut, rfqId,
                                hedgeBuy ? "buy" : "sell", hedgeNotional, futPx, "HEDGE",
                                G10Universe.BOOKS[fut.ccyIndex], true);
                    }
                }
            }
            // Axes: slow directional interest, occasionally refreshed.
            if (rnd.nextDouble() < cfg.axeUpdateProbPerSec) {
                int gi = quotableIdx[rnd.nextInt(quotableIdx.length)];
                Instrument in = instruments.get(gi);
                long ts = secondStartNs + rnd.nextLong(NANOS_PER_SEC) + tsLookaheadNs;
                if (endNs != null && ts >= endNs) {
                    return;
                }
                sender.table(cfg.axesTable())
                        .symbol("instrument_id", in.id)
                        .symbol("ccy", in.ccy)
                        .symbol("tenor_bucket", in.tenorBucket)
                        .symbol("direction", G10Universe.AXE_DIRECTION[rnd.nextInt(2)])
                        .doubleColumn("size", G10Universe.rfqNotional(in.tenorYears))
                        .doubleColumn("skew_bps", (rnd.nextDouble() - 0.5) * 2.0)
                        .longColumn("priority", rnd.nextInt(1, 6))
                        .timestampColumn("active_until", (ts + 3600L * NANOS_PER_SEC) / 1000L, ChronoUnit.MICROS)
                        .at(ts, ChronoUnit.NANOS);
                axeRows.incrementAndGet();
            }
        }

        private void emitTrade(Sender sender, long tsNanos, Instrument in, String rfqId, String side,
                               double notional, double px, String client, String book, boolean isHedge) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            sender.table(cfg.tradesTable())
                    .symbol("rfq_id", rfqId)
                    .symbol("instrument_id", in.id)
                    .symbol("ccy", in.ccy)
                    .symbol("side", side)
                    .symbol("client_id", client)
                    .symbol("book", book)
                    .symbol("venue", venueFor(in))
                    .boolColumn("is_hedge", isHedge)
                    .doubleColumn("notional", notional)
                    .doubleColumn("price", px)
                    .uuidColumn("trade_id", rnd.nextLong(), rnd.nextLong())
                    .at(tsNanos, ChronoUnit.NANOS);
            tradeRows.incrementAndGet();
        }

        // --- helpers ----------------------------------------------------------------

        private int quotableSwap(ThreadLocalRandom rnd) {
            int gi;
            do {
                gi = quotableIdx[rnd.nextInt(quotableIdx.length)];
            } while (!instruments.get(gi).isRate());
            return gi;
        }

        private double priceQuantized(Instrument in, double[] betas) {
            double m = curve.mid(in, betas);
            return in.isRate() ? m : G10Universe.quantizeToTick(m, in.tickSize);
        }

        private double roundIf(Instrument in, double px) {
            return in.isRate() ? px : G10Universe.quantizeToTick(px, in.tickSize);
        }

        private double inventorySkewBps(int gi) {
            // Long risk -> skew to attract offsetting flow. Bounded, small.
            double inv = inventory[gi];
            return Math.max(-2.0, Math.min(2.0, -inv / 5.0e8));
        }

        private double skewInPriceUnits(Instrument in, double skewBps) {
            // 1bp in rate space = 0.01%; in price space ≈ dv01-scaled.
            return in.isRate() ? skewBps * 0.01 : skewBps * in.dv01PerUnit * 100.0;
        }

        private double bpMove(Instrument in, double mid, double prev) {
            return in.isRate() ? Math.abs(mid - prev) / 0.01
                    : Math.abs(mid - prev) / Math.max(1e-9, in.dv01PerUnit * 100.0);
        }

        private double halfSpread(Instrument in) {
            if (in.isRate()) {
                return (0.05 + 0.01 * in.tenorYears) * 0.01;   // 0.05bp..~0.35bp, in %
            }
            return in.tickSize * (0.5 + in.tenorYears / 30.0);
        }

        private int nearestFuture(int ccyIndex, double tenorYears) {
            int[] futs = futuresByCcy[ccyIndex];
            if (futs.length == 0) {
                return -1;
            }
            int best = futs[0];
            double bestD = Double.MAX_VALUE;
            for (int f : futs) {
                double d = Math.abs(instruments.get(f).tenorYears - tenorYears);
                if (d < bestD) {
                    bestD = d;
                    best = f;
                }
            }
            return best;
        }

        private void waitWhilePaused() throws InterruptedException {
            AtomicBoolean flag = kind == Kind.MARKET_DATA ? pausedMd : pausedBiz;
            while (flag.get() && running.get()) {
                Thread.sleep(200);
            }
        }
    }

    private static long[] sortedOffsets(int n) {
        long[] o = new long[n];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            o[i] = rnd.nextLong(NANOS_PER_SEC);
        }
        Arrays.sort(o);
        return o;
    }

    private static String venueFor(Instrument in) {
        if ("FUTURE".equals(in.product)) {
            switch (in.ccy) {
                case "USD": return "CME";
                case "EUR": return "Eurex";
                case "GBP": return "ICE";
                default: return "JPX";
            }
        }
        if ("GOVT".equals(in.product)) {
            return "BrokerTec";
        }
        return "TP_ICAP";
    }

    private static String tag(Kind kind) {
        return kind == Kind.MARKET_DATA ? "md" : "biz";
    }

    // ---------------------------------------------------------------- reporting

    private Thread startThroughputSampler(List<Long> perSecond) {
        Thread t = new Thread(() -> {
            long lastMd = 0, lastBiz = 0, sec = 0;
            try {
                while (running.get()) {
                    Thread.sleep(1000);
                    long nowMd = mdRows.get();
                    long nowBiz = coreRows.get() + quoteRows.get() + tradeRows.get()
                            + rfqRows.get() + axeRows.get();
                    long dMd = nowMd - lastMd;
                    long dBiz = nowBiz - lastBiz;
                    lastMd = nowMd;
                    lastBiz = nowBiz;
                    sec++;
                    perSecond.add(dMd + dBiz);
                    System.out.printf("[rate] t=%ds  md %,d/s  business %,d/s  (total %,d/s)%n",
                            sec, dMd, dBiz, dMd + dBiz);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "qwp-throughput-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private Thread startHeartbeat() {
        Thread t = new Thread(() -> {
            long lastMs = System.currentTimeMillis();
            long lastMd = 0, lastBiz = 0, elapsed = 0;
            try {
                while (running.get()) {
                    long target = 10_000, slept = 0;
                    while (slept < target && running.get()) {
                        long chunk = Math.min(500, target - slept);
                        Thread.sleep(chunk);
                        slept += chunk;
                    }
                    if (!running.get()) {
                        break;
                    }
                    long now = System.currentTimeMillis();
                    double dt = Math.max(1, (now - lastMs) / 1000.0);
                    long nowMd = mdRows.get();
                    long nowBiz = coreRows.get() + quoteRows.get() + tradeRows.get()
                            + rfqRows.get() + axeRows.get();
                    elapsed += Math.round(dt);
                    System.out.printf("[hb] t=%ds  md %,d/s (%,d)  business %,d/s (%,d)  "
                                    + "[core=%,d q=%,d trd=%,d rfq=%,d axe=%,d]%n",
                            elapsed, Math.round((nowMd - lastMd) / dt), nowMd,
                            Math.round((nowBiz - lastBiz) / dt), nowBiz,
                            coreRows.get(), quoteRows.get(), tradeRows.get(), rfqRows.get(), axeRows.get());
                    lastMs = now;
                    lastMd = nowMd;
                    lastBiz = nowBiz;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "qwp-heartbeat");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private Thread startDeadline(int seconds) {
        Thread t = new Thread(() -> {
            try {
                for (int s = 0; s < seconds && running.get(); s++) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running.compareAndSet(true, false)) {
                System.out.printf("[run] reached --run_secs %d, stopping.%n", seconds);
            }
        }, "qwp-deadline");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void printPoolDone(String name, long rows, long finishMs, long startMs) {
        if (rows == 0) {
            return;
        }
        double activeSecs = finishMs > startMs ? (finishMs - startMs) / 1000.0 : 0.0;
        double rate = activeSecs > 0 ? rows / activeSecs : 0.0;
        System.out.printf("  %-12s %,15d rows  (%,.0f/s over %.1fs)%n", name, rows, rate, activeSecs);
    }

    private void printThroughputSummary(List<Long> perSecond) {
        if (perSecond.isEmpty()) {
            return;
        }
        long[] sorted = new long[perSecond.size()];
        long sum = 0;
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = perSecond.get(i);
            sum += sorted[i];
        }
        Arrays.sort(sorted);
        System.out.printf("[throughput] %d samples: min=%,d median=%,d avg=%,.0f max=%,d rows/sec%n",
                sorted.length, sorted[0], sorted[sorted.length / 2],
                (double) sum / sorted.length, sorted[sorted.length - 1]);
    }

    private AtomicLong capCounter() {
        return cfg.marketDataProcesses > 0 ? mdRows : tradeRows;
    }

    private boolean capReached() {
        return cfg.totalMarketDataEvents > 0 && capCounter().get() >= cfg.totalMarketDataEvents;
    }

    private boolean pastEnd(long secondStartNs) {
        return endNsField != null && secondStartNs >= endNsField;
    }

    private Long endNsField;  // set in run() so pastEnd has it without threading through

    // ---------------------------------------------------------------- transport / DDL

    private Sender buildSender(Kind kind, int workerId) {
        Sender.LineSenderBuilder b = Sender.builder(Sender.Transport.WEBSOCKET);
        for (String host : cfg.hosts) {
            b.address(host);
        }
        if (cfg.tls) {
            b.enableTls();
            if (cfg.tlsInsecure) {
                b.advancedTls().disableCertificateValidation();
            }
        }
        if (cfg.token != null) {
            b.httpToken(cfg.token);
        } else if (cfg.user != null) {
            b.httpUsernamePassword(cfg.user, cfg.password);
        }
        String sfPath = cfg.sfDir + "/" + tag(kind) + workerId;
        try {
            Files.createDirectories(Paths.get(sfPath));
        } catch (Exception e) {
            System.err.printf("[%s%d] WARN: could not pre-create sf dir %s: %s%n",
                    tag(kind), workerId, sfPath, e.getMessage());
        }
        final AtomicLong lastConnLogMs = new AtomicLong(0);
        final String who = tag(kind) + workerId;
        b.storeAndForwardDir(sfPath)
                .senderId(cfg.senderId + "-" + who)
                .transactional(true)
                .reconnectMaxDurationMillis(300_000)
                .reconnectInitialBackoffMillis(100)
                .reconnectMaxBackoffMillis(5_000)
                .autoFlushBytes(cfg.autoFlushBytes)
                .autoFlushRows(1_000_000)
                .autoFlushIntervalMillis(1_000)
                .errorHandler(error -> System.err.printf("[%s %s] BATCH ERROR: category=%s table=%s msg=%s%n",
                        who, LocalDateTime.now().format(FMT), error.getCategory(),
                        error.getTableName(), error.getServerMessage()))
                .connectionListener(event -> {
                    long now = System.currentTimeMillis();
                    long prev = lastConnLogMs.get();
                    if (now - prev >= 1000 && lastConnLogMs.compareAndSet(prev, now)) {
                        System.out.printf("[%s %s] CONNECTION: %s host=%s:%d%n",
                                who, LocalDateTime.now().format(FMT), event.getKind(),
                                event.getHost(), event.getPort());
                    }
                });
        return b.build();
    }

    private String retentionClause(String ossTtl) {
        if (!cfg.shortTtl) {
            return "";
        }
        return cfg.enterprise ? " STORAGE POLICY(" + ENTERPRISE_POLICY + ")" : " TTL " + ossTtl;
    }

    private String mvRetentionClause(String ossTtl) {
        return cfg.shortTtl ? " TTL " + ossTtl : "";   // matviews take TTL only, even on enterprise
    }

    private void createTables() {
        // Static dimension (seeded over ILP right after creation).
        execDdl(cfg.instrumentsTable(), "CREATE TABLE IF NOT EXISTS " + cfg.instrumentsTable() + " ("
                + "load_ts TIMESTAMP, instrument_id SYMBOL CAPACITY 4096 INDEX, ccy SYMBOL, product SYMBOL, "
                + "benchmark SYMBOL, tenor SYMBOL, tenor_years DOUBLE, tenor_bucket SYMBOL, price_space SYMBOL, "
                + "tick_size DOUBLE, coupon DOUBLE, dv01_per_unit DOUBLE, is_hedge BOOLEAN"
                + ") timestamp(load_ts) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(load_ts, instrument_id)");

        if (cfg.marketDataProcesses > 0) {
            execDdl(cfg.marketDataTable(), "CREATE TABLE IF NOT EXISTS " + cfg.marketDataTable() + " ("
                    + "timestamp TIMESTAMP PARQUET(delta_binary_packed, zstd(4)), "
                    + "instrument_id SYMBOL CAPACITY 4096 PARQUET(rle_dictionary, zstd(4), bloom_filter), "
                    + "venue SYMBOL PARQUET(rle_dictionary, zstd(4)), basis SYMBOL PARQUET(rle_dictionary, zstd(4)), "
                    + "bids DOUBLE[][] PARQUET(default, zstd(4)), asks DOUBLE[][] PARQUET(default, zstd(4)), "
                    + "best_bid DOUBLE PARQUET(default, zstd(4)), best_ask DOUBLE PARQUET(default, zstd(4))"
                    + ") timestamp(timestamp) PARTITION BY HOUR" + retentionClause("3 DAYS"));
        }
        if (cfg.businessProcesses > 0) {
            execDdl(cfg.corePriceTable(), "CREATE TABLE IF NOT EXISTS " + cfg.corePriceTable() + " ("
                    + "timestamp TIMESTAMP PARQUET(delta_binary_packed, zstd(4)), "
                    + "instrument_id SYMBOL CAPACITY 4096 PARQUET(rle_dictionary, zstd(4), bloom_filter), "
                    + "ccy SYMBOL PARQUET(rle_dictionary, zstd(4)), basis SYMBOL PARQUET(rle_dictionary, zstd(4)), "
                    + "bid DOUBLE, ask DOUBLE, mid DOUBLE, bid_sz DOUBLE, ask_sz DOUBLE"
                    + ") timestamp(timestamp) PARTITION BY HOUR" + retentionClause("3 DAYS"));

            execDdl(cfg.quotesTable(), "CREATE TABLE IF NOT EXISTS " + cfg.quotesTable() + " ("
                    + "timestamp TIMESTAMP, quote_id UUID, rfq_id SYMBOL, "
                    + "instrument_id SYMBOL CAPACITY 4096, ccy SYMBOL, platform SYMBOL, "
                    + "bid DOUBLE, ask DOUBLE, mid DOUBLE, skew_bps DOUBLE, valid_until TIMESTAMP"
                    + ") timestamp(timestamp) PARTITION BY HOUR" + retentionClause("3 DAYS")
                    + " DEDUP UPSERT KEYS(timestamp, quote_id)");

            execDdl(cfg.tradesTable(), "CREATE TABLE IF NOT EXISTS " + cfg.tradesTable() + " ("
                    + "timestamp TIMESTAMP_NS, trade_id UUID, rfq_id SYMBOL, "
                    + "instrument_id SYMBOL CAPACITY 4096, ccy SYMBOL, side SYMBOL, "
                    + "notional DOUBLE, price DOUBLE, client_id SYMBOL, book SYMBOL, venue SYMBOL, is_hedge BOOLEAN"
                    + ") timestamp(timestamp) PARTITION BY HOUR" + retentionClause("1 MONTH")
                    + " DEDUP UPSERT KEYS(timestamp, trade_id)");

            execDdl(cfg.rfqsTable(), "CREATE TABLE IF NOT EXISTS " + cfg.rfqsTable() + " ("
                    + "timestamp TIMESTAMP, rfq_id SYMBOL, client_id SYMBOL, platform SYMBOL, ccy SYMBOL, "
                    + "tenor SYMBOL, instrument_id SYMBOL CAPACITY 4096, side SYMBOL, notional DOUBLE, status SYMBOL"
                    + ") timestamp(timestamp) PARTITION BY DAY" + retentionClause("1 MONTH"));

            execDdl(cfg.axesTable(), "CREATE TABLE IF NOT EXISTS " + cfg.axesTable() + " ("
                    + "timestamp TIMESTAMP, instrument_id SYMBOL CAPACITY 4096, ccy SYMBOL, tenor_bucket SYMBOL, "
                    + "direction SYMBOL, size DOUBLE, skew_bps DOUBLE, priority LONG, active_until TIMESTAMP"
                    + ") timestamp(timestamp) PARTITION BY DAY" + retentionClause("1 MONTH"));
        }
    }

    /**
     * Derived layer (§14.1 reconciled): {@code g10_positions} is a regular VIEW (running
     * net over trades — cannot be an MV); {@code g10_pos_risk} is a trade-driven
     * SAMPLE-BY MV of risk <i>flow</i> per interval (NOT current stock — the hero query
     * derives stock from the positions view). Plus cheap rollups; the only IMMEDIATE
     * refreshes hang off the low-volume bases, never the depth feed (§13.6).
     */
    private void createViews() {
        if (!cfg.createViews || cfg.businessProcesses <= 0) {
            return;
        }
        String trades = cfg.tradesTable();
        String instr = cfg.instrumentsTable();
        String core = cfg.corePriceTable();

        execDdl("g10_positions" + cfg.suffix, "CREATE VIEW IF NOT EXISTS 'g10_positions" + cfg.suffix + "' AS ("
                + "SELECT timestamp, book, instrument_id, "
                + "sum(CASE WHEN side='buy' THEN notional ELSE -notional END) "
                + "OVER (PARTITION BY book, instrument_id ORDER BY timestamp) AS net_notional "
                + "FROM " + trades + ")");

        // Trade-driven MV: risk FLOW per (ccy, tenor_bucket, book) per second. WITH BASE
        // is required because the query joins the instruments dimension.
        execDdl("g10_pos_risk" + cfg.suffix, "CREATE MATERIALIZED VIEW IF NOT EXISTS 'g10_pos_risk" + cfg.suffix
                + "' WITH BASE '" + trades + "' REFRESH IMMEDIATE AS ("
                + "SELECT t.timestamp, t.book, i.ccy, i.tenor_bucket, "
                + "sum((CASE WHEN t.side='buy' THEN 1 ELSE -1 END) * t.notional * i.dv01_per_unit) AS dv01_flow "
                + "FROM " + trades + " t JOIN " + instr + " i ON t.instrument_id = i.instrument_id "
                + "SAMPLE BY 1s) PARTITION BY HOUR" + mvRetentionClause("3 DAYS"));

        // Curve-mid candles (IMMEDIATE off the low-volume core_price table).
        execDdl("g10_curve_mid_1m" + cfg.suffix, "CREATE MATERIALIZED VIEW IF NOT EXISTS 'g10_curve_mid_1m"
                + cfg.suffix + "' WITH BASE '" + core + "' REFRESH IMMEDIATE AS ("
                + "SELECT timestamp, instrument_id, first(mid) AS open, max(mid) AS high, "
                + "min(mid) AS low, last(mid) AS close FROM " + core + " SAMPLE BY 1m"
                + ") PARTITION BY HOUR" + mvRetentionClause("3 DAYS"));

        // BBO rollup over the depth feed — TIMER refresh, never IMMEDIATE (§13.6).
        if (cfg.marketDataProcesses > 0) {
            String md = cfg.marketDataTable();
            execDdl("g10_bbo_1m" + cfg.suffix, "CREATE MATERIALIZED VIEW IF NOT EXISTS 'g10_bbo_1m" + cfg.suffix
                    + "' WITH BASE '" + md + "' REFRESH EVERY 1m AS ("
                    + "SELECT timestamp, instrument_id, max(best_bid) AS bid, min(best_ask) AS ask "
                    + "FROM " + md + " SAMPLE BY 1m) PARTITION BY DAY" + mvRetentionClause("3 DAYS"));
        }
    }

    /** Seed the static dimension over ILP (uses the known-good ingestion path). */
    private void seedInstruments() {
        try (Sender sender = buildSender(Kind.BUSINESS, 99)) {
            for (Instrument in : instruments) {
                sender.table(cfg.instrumentsTable())
                        .symbol("instrument_id", in.id)
                        .symbol("ccy", in.ccy)
                        .symbol("product", in.product)
                        .symbol("benchmark", in.benchmark.isEmpty() ? "NA" : in.benchmark)
                        .symbol("tenor", in.tenor)
                        .symbol("tenor_bucket", in.tenorBucket)
                        .symbol("price_space", in.priceSpace)
                        .doubleColumn("tenor_years", in.tenorYears)
                        .doubleColumn("tick_size", in.tickSize)
                        .doubleColumn("coupon", in.coupon)
                        .doubleColumn("dv01_per_unit", in.dv01PerUnit)
                        .boolColumn("is_hedge", in.hedge)
                        .at(INSTRUMENTS_LOAD_NS, ChronoUnit.NANOS);
            }
            sender.flush();
        } catch (Exception e) {
            System.err.println("[seed] could not seed instruments: " + e.getMessage());
        }
        System.out.printf("[seed] %d instruments seeded into %s%n", instruments.size(), cfg.instrumentsTable());
    }

    private void execDdl(String obj, String ddl) {
        System.out.println("[INFO] ensuring " + obj + " ...");
        try (QwpQueryClient client = QwpQueryClient.fromConfig(cfg.queryClientConfig())) {
            client.connect();
            client.execute(ddl, new QwpColumnBatchHandler() {
                @Override
                public void onBatch(QwpColumnBatch batch) {
                }

                @Override
                public void onEnd(long totalRows) {
                }

                @Override
                public void onError(byte status, String message) {
                    System.err.println("[DDL " + obj + "] ERROR: " + message);
                }

                @Override
                public void onExecDone(short opType, long rowsAffected) {
                    System.out.println("[DDL] " + obj + " ready.");
                }
            });
        }
    }

    // ---------------------------------------------------------------- WAL backpressure

    private Thread startWalMonitor() {
        final List<AtomicBoolean> flags = new ArrayList<>();
        final List<String> tables = new ArrayList<>();
        final List<Integer> thresholds = new ArrayList<>();
        if (cfg.marketDataProcesses > 0) {
            flags.add(pausedMd);
            tables.add(cfg.marketDataTable());
            thresholds.add((cfg.marketDataProcesses > 2 ? 3 : 5) * cfg.marketDataProcesses);
        }
        if (cfg.businessProcesses > 0) {
            flags.add(pausedBiz);
            tables.add(cfg.tradesTable());   // representative low-volume base
            thresholds.add(5);
        }
        Thread t = new Thread(() -> {
            try (QwpQueryClient client = QwpQueryClient.fromConfig(cfg.queryClientConfig())) {
                client.connect();
                while (running.get()) {
                    boolean anyPaused = false;
                    for (int i = 0; i < tables.size(); i++) {
                        long[] lag = queryWalLag(client, tables.get(i));
                        if (lag == null) {
                            continue;
                        }
                        long diff = lag[0] - lag[1];
                        AtomicBoolean flag = flags.get(i);
                        int highWater = thresholds.get(i);
                        long lowWater = highWater / 2;
                        if (diff > highWater) {
                            if (flag.compareAndSet(false, true)) {
                                System.out.printf("[wal] %s lag %d > %d, pausing pool%n",
                                        tables.get(i), diff, highWater);
                            }
                        } else if (diff <= lowWater) {
                            if (flag.compareAndSet(true, false)) {
                                System.out.printf("[wal] %s drained to %d, resuming pool%n",
                                        tables.get(i), diff);
                            }
                        }
                        if (flag.get()) {
                            anyPaused = true;
                        }
                    }
                    long sleepMs = anyPaused ? 250 : 5000;
                    long remaining = sleepMs;
                    while (remaining > 0 && running.get()) {
                        long chunk = Math.min(remaining, 500);
                        Thread.sleep(chunk);
                        remaining -= chunk;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[wal] monitor stopped: " + e.getMessage());
            }
        }, "qwp-wal-monitor");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private long[] queryWalLag(QwpQueryClient client, String table) {
        final long[] out = {Long.MIN_VALUE, Long.MIN_VALUE};
        try {
            client.execute("SELECT sequencerTxn, writerTxn FROM wal_tables() WHERE name = '" + table + "'",
                    new QwpColumnBatchHandler() {
                        @Override
                        public void onBatch(QwpColumnBatch batch) {
                            batch.forEachRow(row -> {
                                out[0] = row.getLongValue(0);
                                out[1] = row.getLongValue(1);
                            });
                        }

                        @Override
                        public void onEnd(long totalRows) {
                        }

                        @Override
                        public void onError(byte status, String message) {
                        }
                    });
        } catch (Exception e) {
            return null;
        }
        return out[0] == Long.MIN_VALUE ? null : out;
    }

    // ---------------------------------------------------------------- start / helpers

    private long resolveStartNanos(Long endNs) {
        this.endNsField = endNs;
        boolean ftl = "faster-than-life".equals(cfg.mode);
        Long latest = latestAcrossTables();
        if (ftl) {
            long startNs = cfg.startTs != null ? isoToNanos(cfg.startTs)
                    : (latest != null ? latest + 1_000L : nowNs());
            if (latest != null && latest + 1_000L > startNs) {
                System.out.printf("[INFO] Advancing start from %s to %s to avoid overlap.%n",
                        nanosToIso(startNs), nanosToIso(latest + 1_000L));
                startNs = latest + 1_000L;
            }
            if (endNs != null && latest != null && endNs <= latest) {
                System.err.printf("[ERROR] --end_ts (%s) is at/before latest data (%s); would ingest out-of-order. Aborting.%n",
                        cfg.endTs, nanosToIso(latest));
                System.exit(1);
            }
            if (endNs != null && startNs >= endNs) {
                System.out.println("[INFO] Requested range already present. Exiting.");
                System.exit(0);
            }
            return startNs;
        }
        if (latest != null) {
            long next = latest + 1_000L;
            long now = nowNs();
            if (next > now) {
                double waitSecs = (next - now) / 1e9;
                System.out.printf("[INFO] Last row is %s. Waiting %.1fs to avoid overlap...%n",
                        nanosToIso(latest), waitSecs);
                try {
                    Thread.sleep((long) Math.ceil(waitSecs * 1000.0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        long startNs = nowNs();
        if (endNs != null && startNs >= endNs) {
            System.out.println("[INFO] now >= --end_ts. Exiting.");
            System.exit(0);
        }
        return startNs;
    }

    private Long latestAcrossTables() {
        Long latest = null;
        if (cfg.marketDataProcesses > 0) {
            latest = maxOf(latest, readMaxTimestampNanos(cfg.marketDataTable(), true));
        }
        if (cfg.businessProcesses > 0) {
            latest = maxOf(latest, readMaxTimestampNanos(cfg.tradesTable(), false)); // ns
            latest = maxOf(latest, readMaxTimestampNanos(cfg.corePriceTable(), true)); // micros
        }
        return latest;
    }

    private static long nowNs() {
        return Instant.now().toEpochMilli() * 1_000_000L;
    }

    private static String nanosToIso(long ns) {
        return Instant.ofEpochSecond(ns / NANOS_PER_SEC, ns % NANOS_PER_SEC).toString();
    }

    private static Long maxOf(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    private Long readMaxTimestampNanos(String table, boolean micros) {
        final AtomicLong out = new AtomicLong(Long.MIN_VALUE);
        try (QwpQueryClient client = QwpQueryClient.fromConfig(cfg.queryClientConfig())) {
            client.connect();
            client.execute("SELECT max(timestamp) FROM " + table,
                    new QwpColumnBatchHandler() {
                        @Override
                        public void onBatch(QwpColumnBatch batch) {
                            batch.forEachRow(row -> out.set(row.getLongValue(0)));
                        }

                        @Override
                        public void onEnd(long totalRows) {
                        }

                        @Override
                        public void onError(byte status, String message) {
                        }
                    });
        } catch (Exception e) {
            return null;
        }
        long v = out.get();
        if (v == Long.MIN_VALUE || v <= 0) {
            return null;
        }
        long ns = micros ? v * 1000L : v;
        long nowNs = nowNs();
        long twoYears = 2L * 365 * 24 * 3600 * NANOS_PER_SEC;
        return Math.abs(ns - nowNs) > twoYears ? null : ns;
    }

    private static long alignToSecond(long ns) {
        return (ns / NANOS_PER_SEC) * NANOS_PER_SEC;
    }

    private static long isoToNanos(String iso) {
        OffsetDateTime odt;
        try {
            odt = OffsetDateTime.parse(iso);
        } catch (Exception e) {
            odt = LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC);
        }
        Instant in = odt.toInstant();
        return in.getEpochSecond() * NANOS_PER_SEC + in.getNano();
    }
}
