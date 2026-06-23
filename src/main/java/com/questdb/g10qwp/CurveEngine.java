package com.questdb.g10qwp;

import java.util.List;
import java.util.SplittableRandom;

/**
 * The deterministic per-currency curve engine — the load-bearing idea of the whole
 * generator (spec §13.1). For G10 the "entity" that random-walks is not the
 * instrument but the <b>curve</b>: each currency's curve is a 3-factor
 * Nelson-Siegel state {@code (b0, b1, b2)} = (level, slope, curvature) that is
 * walked once per data-second and reconstructed into every pillar.
 *
 * <p><b>Why this shape.</b> A naive independent walk per pillar produces curves that
 * cross and kink (the quant smell-test failure §2/§5 warn about). Driving the whole
 * curve from a few smooth factors keeps it arbitrage-sane, and because the betas are
 * seeded deterministically and advanced purely as a function of the data-second,
 * <b>every worker in every process reconstructs the identical curve for
 * {@code (ccy, second)} with no shared state</b> — so depth, core mids, quotes,
 * trades and the bump-reprice DV01 all derive from one curve and the hero ASOF join
 * is coherent by construction, not by luck.
 *
 * <p><b>Continuity (§13.2).</b> We carry the <i>close</i> betas of second {@code k}
 * forward as the <i>open</i> betas of {@code k+1}, and interpolate betas open→close
 * within a second by event ordinal. The whole curve is therefore continuous
 * second-to-second and across backfill chunk seams.
 *
 * <p><b>Cross-market correlation (§7).</b> Each second draws a single US-led level
 * shock; every currency loads on it with its own coefficient plus an idiosyncratic
 * component, so curves move together intraday the way real rates markets do.
 *
 * <p><b>Negative rates (§14.6).</b> Betas, yields and forwards are never clamped to
 * be positive — JPY/EUR can and do go negative.
 */
public final class CurveEngine {

    public static final double LAMBDA = 1.8;          // NS decay; fixed
    private static final long CURVE_SEED = 0xC0FFEE5EEDL;
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    // Per-second factor vols (in % units). Tuned for a visibly-moving demo (a "busy
    // day", ~2bp/min on the belly); lower these for a calmer, more conservative tape.
    private static final double SIG_LEVEL = 0.0024;
    private static final double SIG_SLOPE = 0.0020;
    private static final double SIG_CURV = 0.0036;
    private static final double SHOCK_PROB = 0.01;
    private static final double SHOCK_MULT = 6.0;

    // Cross-market loadings on the US level factor (USD leads).
    private static final double[] LEVEL_LOADING = {1.00, 0.80, 0.90, 0.50};

    // Seed (EOD-anchored "spine") betas per currency: {level, slope, curvature}.
    private static final double[][] SEED_BETAS = {
            {4.30, -0.55, -0.90},   // USD: ~3.75 short, ~4.3 long, mild hump/inversion
            {2.60, -0.85, -0.60},   // EUR
            {4.20, -0.45, -0.70},   // GBP
            {1.10, -1.00, 0.40},    // JPY: very low short end
    };

    private final int nCcy = G10Universe.CCYS.length;
    private final double[][] walk = new double[nCcy][3];   // current carried state
    private final double[][] open = new double[nCcy][3];   // this second's open
    private final double[][] close = new double[nCcy][3];  // this second's close
    private final SplittableRandom levelRng;               // shared US-led shock stream
    private final SplittableRandom[] idioRng = new SplittableRandom[nCcy];

    public CurveEngine() {
        levelRng = new SplittableRandom(CURVE_SEED);
        for (int c = 0; c < nCcy; c++) {
            System.arraycopy(SEED_BETAS[c], 0, walk[c], 0, 3);
            idioRng[c] = new SplittableRandom(CURVE_SEED * 31 + GOLDEN * (c + 1));
        }
    }

    /** Seed betas of currency {@code c} as of run start (for the one-off DV01 seed). */
    public double[] seedBetas(int c) {
        return SEED_BETAS[c].clone();
    }

    /**
     * Advance every currency's curve once for data-second {@code k}: snapshot open =
     * carried state, then evolve close. The level shock is shared (US-led); slope and
     * curvature are idiosyncratic. Deterministic in {@code k} via the seeded streams.
     */
    public void beginSecond(long k) {
        double levelShock = SIG_LEVEL * levelRng.nextGaussian()
                * (levelRng.nextDouble() < SHOCK_PROB ? SHOCK_MULT : 1.0);
        for (int c = 0; c < nCcy; c++) {
            System.arraycopy(walk[c], 0, open[c], 0, 3);
            SplittableRandom r = idioRng[c];
            double db0 = LEVEL_LOADING[c] * levelShock + 0.3 * SIG_LEVEL * r.nextGaussian();
            double db1 = SIG_SLOPE * r.nextGaussian() * (r.nextDouble() < SHOCK_PROB ? SHOCK_MULT : 1.0);
            double db2 = SIG_CURV * r.nextGaussian() * (r.nextDouble() < SHOCK_PROB ? SHOCK_MULT : 1.0);
            close[c][0] = clampLevel(open[c][0] + db0);
            close[c][1] = open[c][1] + db1;
            close[c][2] = open[c][2] + db2;
        }
    }

    /** Carry this second's close betas forward as the next second's open (continuity). */
    public void endSecond() {
        for (int c = 0; c < nCcy; c++) {
            System.arraycopy(close[c], 0, walk[c], 0, 3);
        }
    }

    public double[] openBetas(int c) {
        return open[c];
    }

    public double[] closeBetas(int c) {
        return close[c];
    }

    /** Betas of currency {@code c} interpolated open→close by fraction in [0,1]. */
    public double[] betasAt(int c, double frac) {
        double[] o = open[c];
        double[] cl = close[c];
        return new double[]{
                o[0] + frac * (cl[0] - o[0]),
                o[1] + frac * (cl[1] - o[1]),
                o[2] + frac * (cl[2] - o[2]),
        };
    }

    // ---------------------------------------------------------------- NS + pricer

    /** Nelson-Siegel zero rate (%) at tenor {@code t} years for the given betas. */
    public static double zeroRate(double[] beta, double t) {
        double x = t / LAMBDA;
        double e = Math.exp(-x);
        double term1 = x < 1e-9 ? 1.0 : (1 - e) / x;
        double term2 = term1 - e;
        return beta[0] + beta[1] * term1 + beta[2] * term2;
    }

    /** Discount factor at {@code t} years, with an optional parallel curve shift {@code dz} (%). */
    private static double df(double[] beta, double t, double dz) {
        double z = zeroRate(beta, t) + dz;
        return Math.exp(-(z / 100.0) * t);
    }

    /** Annuity (Σ discount factors of the fixed leg), annual periods, optional shift. */
    private static double annuity(double[] beta, double T, double dz) {
        if (T < 1.0) {
            return T * df(beta, T, dz);
        }
        int n = (int) Math.round(T);
        double a = 0;
        for (int i = 1; i <= n; i++) {
            a += df(beta, i, dz);
        }
        return a;
    }

    /** Par swap rate (%) at tenor {@code T} from the curve. */
    public static double swapParRate(double[] beta, double T, double dz) {
        double last = T < 1.0 ? df(beta, T, dz) : df(beta, Math.round(T), dz);
        return 100.0 * (1 - last) / annuity(beta, T, dz);
    }

    /** Clean price (per 100 face) of an annual-coupon bond/future proxy off the curve. */
    public static double bondPrice(double[] beta, double coupon, double T, double dz) {
        return 100.0 * (coupon * annuity(beta, T, dz) + df(beta, Math.round(Math.max(1, T)), dz));
    }

    /** Quoted mid for an instrument off the given curve betas (rate % or clean price). */
    public double mid(Instrument inst, double[] beta) {
        if (inst.isRate()) {
            return swapParRate(beta, inst.tenorYears, 0.0);
        }
        return bondPrice(beta, inst.coupon, inst.tenorYears, 0.0);
    }

    /**
     * DV01 per 1 unit of notional, by <b>bump-and-reprice</b> the constructed curve by
     * 1bp (§8.5/§14.4): the risk number is correct <i>given</i> the prices, and because
     * the curve is deterministic the DV01 is reproducible across tables and runs.
     */
    public double dv01PerUnit(Instrument inst, double[] beta) {
        final double bp = 0.01; // 1bp in % units
        if (inst.isRate()) {
            // Receiver-of-fixed PV per unit notional at the original par coupon, bumped.
            double c0 = swapParRate(beta, inst.tenorYears, 0.0) / 100.0;
            double pv0 = c0 * annuity(beta, inst.tenorYears, 0.0)
                    + df(beta, lastT(inst.tenorYears), 0.0);
            double pvUp = c0 * annuity(beta, inst.tenorYears, bp)
                    + df(beta, lastT(inst.tenorYears), bp);
            return Math.abs(pv0 - pvUp);  // ≈ annuity * 1e-4
        }
        // Bond/future: change in clean price (per 100 face) for +1bp, per 1 face unit.
        double p0 = bondPrice(beta, inst.coupon, inst.tenorYears, 0.0);
        double pUp = bondPrice(beta, inst.coupon, inst.tenorYears, bp);
        return Math.abs(p0 - pUp) / 100.0;
    }

    private static double lastT(double T) {
        return T < 1.0 ? T : Math.round(T);
    }

    /** One-off: stamp each instrument's seed {@code dv01PerUnit} from the seed curve. */
    public void seedDv01(List<Instrument> instruments) {
        for (Instrument inst : instruments) {
            inst.dv01PerUnit = dv01PerUnit(inst, seedBetas(inst.ccyIndex));
        }
    }

    private static double clampLevel(double b0) {
        return Math.max(-1.0, Math.min(12.0, b0));
    }
}
