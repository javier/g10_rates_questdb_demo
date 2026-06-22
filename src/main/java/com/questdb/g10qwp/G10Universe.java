package com.questdb.g10qwp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The G10 rates universe: the instrument set, the desks/clients/platforms/venues,
 * and the per-row noise helpers. The price model itself lives in {@link CurveEngine};
 * this class is the static reference data plus small sampling utilities (the rates
 * analogue of {@code FxUniverse}).
 */
public final class G10Universe {

    /** Liquid core currencies (USD fully real; others EOD-anchored + factor-ticked). */
    public static final String[] CCYS = {"USD", "EUR", "GBP", "JPY"};
    public static final String[] BENCHMARKS = {"SOFR", "ESTR", "SONIA", "TONA"};

    /** Quote-distribution platforms — each a distinct streaming-quote row. */
    public static final String[] PLATFORMS = {"Tradeweb", "Bloomberg", "360T"};
    /** Execution venues for the hedge legs / depth feed. */
    public static final String[] VENUES = {"CME", "Eurex", "ICE", "BrokerTec", "TP_ICAP"};
    public static final String[] BOOKS = {"USD_RATES", "EUR_RATES", "GBP_RATES", "JPY_RATES"};
    public static final String[] RFQ_STATUS = {"received", "quoted", "dealt", "passed"};
    public static final String[] AXE_DIRECTION = {"give", "take"};

    /** Swap curve pillars (years) and labels — quoted in rate space. */
    private static final double[] PILLAR_YEARS =
            {1.0 / 12, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30};
    private static final String[] PILLAR_LABELS =
            {"1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y", "30Y"};

    private G10Universe() {
    }

    /** Build the full instrument universe: swaps (curve) + govvies + futures (hedge). */
    public static List<Instrument> instruments() {
        List<Instrument> out = new ArrayList<>();
        for (int c = 0; c < CCYS.length; c++) {
            String ccy = CCYS[c];
            String bench = BENCHMARKS[c];
            // --- Swaps (OIS on the RFR) across every pillar: the quotable curve. ---
            for (int p = 0; p < PILLAR_YEARS.length; p++) {
                double ty = PILLAR_YEARS[p];
                int rank = ty >= 2 && ty <= 10 ? 1 : 3;   // belly most liquid
                out.add(new Instrument(
                        ccy + "_OIS_" + PILLAR_LABELS[p], ccy, c, "OIS", bench,
                        PILLAR_LABELS[p], ty, "rate", 0.0001 /* 0.1bp */, 0.0, false, rank));
            }
            // --- Govvies (on-the-run benchmarks), price space, the hedge leg. ---
            for (GovvySpec g : govvies(ccy)) {
                out.add(new Instrument(
                        g.id, ccy, c, "GOVT", "", g.tenorLabel, g.tenorYears, "price",
                        g.tickSize, g.coupon, true, 1));
            }
            // --- Futures (the most liquid hedge), price space. ---
            for (FutSpec f : futures(ccy)) {
                out.add(new Instrument(
                        f.id, ccy, c, "FUTURE", "", f.tenorLabel, f.tenorYears, "price",
                        f.tickSize, f.coupon, true, 1));
            }
        }
        return out;
    }

    private record GovvySpec(String id, String tenorLabel, double tenorYears, double coupon, double tickSize) {
    }

    private record FutSpec(String id, String tenorLabel, double tenorYears, double coupon, double tickSize) {
    }

    // Treasuries trade in 32nds (~0.03125); Bund/Gilt/JGB in 0.01. Coupons are
    // rough on-the-run levels; the curve drives the price, the coupon sets carry.
    private static final double T32 = 1.0 / 32.0;

    private static List<GovvySpec> govvies(String ccy) {
        List<GovvySpec> g = new ArrayList<>();
        switch (ccy) {
            case "USD":
                g.add(new GovvySpec("USD_UST_2Y", "2Y", 2, 0.040, T32));
                g.add(new GovvySpec("USD_UST_5Y", "5Y", 5, 0.040, T32));
                g.add(new GovvySpec("USD_UST_10Y", "10Y", 10, 0.0425, T32));
                g.add(new GovvySpec("USD_UST_30Y", "30Y", 30, 0.0450, T32));
                break;
            case "EUR":
                g.add(new GovvySpec("EUR_SCHATZ_2Y", "2Y", 2, 0.025, 0.01));
                g.add(new GovvySpec("EUR_BOBL_5Y", "5Y", 5, 0.025, 0.01));
                g.add(new GovvySpec("EUR_BUND_10Y", "10Y", 10, 0.026, 0.01));
                g.add(new GovvySpec("EUR_BUXL_30Y", "30Y", 30, 0.028, 0.01));
                break;
            case "GBP":
                g.add(new GovvySpec("GBP_GILT_2Y", "2Y", 2, 0.040, 0.01));
                g.add(new GovvySpec("GBP_GILT_5Y", "5Y", 5, 0.040, 0.01));
                g.add(new GovvySpec("GBP_GILT_10Y", "10Y", 10, 0.0425, 0.01));
                g.add(new GovvySpec("GBP_GILT_30Y", "30Y", 30, 0.0450, 0.01));
                break;
            default: // JPY
                g.add(new GovvySpec("JPY_JGB_2Y", "2Y", 2, 0.005, 0.01));
                g.add(new GovvySpec("JPY_JGB_5Y", "5Y", 5, 0.005, 0.01));
                g.add(new GovvySpec("JPY_JGB_10Y", "10Y", 10, 0.010, 0.01));
                g.add(new GovvySpec("JPY_JGB_30Y", "30Y", 30, 0.018, 0.01));
                break;
        }
        return g;
    }

    private static List<FutSpec> futures(String ccy) {
        List<FutSpec> f = new ArrayList<>();
        switch (ccy) {
            case "USD": // CME complex
                f.add(new FutSpec("USD_ZT", "2Y", 2, 0.040, T32 / 4));
                f.add(new FutSpec("USD_ZF", "5Y", 5, 0.040, T32 / 4));
                f.add(new FutSpec("USD_ZN", "10Y", 7, 0.06, T32 / 2));
                f.add(new FutSpec("USD_TN", "10Y", 10, 0.06, T32 / 2));
                f.add(new FutSpec("USD_ZB", "20Y", 20, 0.06, T32));
                f.add(new FutSpec("USD_UB", "30Y", 30, 0.06, T32));
                break;
            case "EUR": // Eurex
                f.add(new FutSpec("EUR_FGBS", "2Y", 2, 0.06, 0.01));
                f.add(new FutSpec("EUR_FGBM", "5Y", 5, 0.06, 0.01));
                f.add(new FutSpec("EUR_FGBL", "10Y", 10, 0.06, 0.01));
                f.add(new FutSpec("EUR_FGBX", "30Y", 30, 0.04, 0.01));
                break;
            case "GBP": // ICE
                f.add(new FutSpec("GBP_GLONG", "10Y", 10, 0.04, 0.01));
                break;
            default: // JPY
                f.add(new FutSpec("JPY_JGBL", "10Y", 10, 0.01, 0.01));
                break;
        }
        return f;
    }

    // ------------------------------------------------------------- noise helpers

    /** Deterministic fake client pool: BANK/HF/CORP/RM ids, stable across runs. */
    public static String[] clientPool(int count) {
        String[] kinds = {"HF", "BANK", "CORP", "RM", "PENSION"};
        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            ids[i] = "CLI_" + kinds[i % kinds.length] + "_" + String.format("%04d", i);
        }
        return ids;
    }

    /** Round a price to the instrument's tick (32nds for USTs, 0.01 for Bund etc.). */
    public static double quantizeToTick(double px, double tick) {
        if (tick <= 0) {
            return px;
        }
        return Math.round(px / tick) * tick;
    }

    /** Log-normal client ticket size: rates deal in fewer, larger clips. */
    public static double rfqNotional(double tenorYears) {
        // Shorter tenors trade larger notionals (DV01-equivalent sizing).
        double mu = Math.log(tenorYears <= 3 ? 200_000_000.0 : 75_000_000.0);
        double sigma = 0.7;
        double n = Math.exp(mu + sigma * ThreadLocalRandom.current().nextGaussian());
        n = Math.max(5_000_000.0, Math.min(1_000_000_000.0, n));
        return Math.round(n / 1_000_000.0) * 1_000_000.0;   // round to 1mm
    }

    /** Log-scaled depth volume ladder (best levels thinner, deeper levels heavier). */
    public static long[] makeVolumeLadder() {
        int bands = 50;
        double vMin = 1_000_000, vMax = 2_000_000_000.0;   // rates futures clips are large
        double step = (Math.log10(vMax) - Math.log10(vMin)) / (bands - 1);
        long[] ladder = new long[bands];
        for (int i = 0; i < bands; i++) {
            ladder[i] = Math.round(Math.pow(10, Math.log10(vMin) + i * step));
        }
        return ladder;
    }

    public static long volumeForLevel(int level, long[] ladder) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int i = Math.min(level, ladder.length - 1);
        if (i == 0) {
            return rnd.nextLong(ladder[0] / 2, ladder[0] + 1);
        }
        return rnd.nextLong(ladder[i - 1], ladder[i] + 1);
    }
}
