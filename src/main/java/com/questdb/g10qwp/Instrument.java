package com.questdb.g10qwp;

/**
 * One G10 rates instrument: a point on a currency's curve (swap), or a liquid
 * hedge instrument quoted in price space (govvy / future).
 *
 * <p>Everything an instrument needs to be priced off the shared curve lives here:
 * its currency (so the right {@link CurveEngine} curve is used), its tenor in
 * <b>years</b> (for curve math and bucketing — note {@code tenor} as a label sorts
 * lexically, so {@link #tenorYears} is the numeric key), its price space, tick size,
 * and the seed {@code dv01PerUnit} computed by bump-and-reprice off the seed curve.
 *
 * <p>{@link #hedge} marks the liquid futures/govvies that carry the depth firehose
 * ({@code g10_market_data}); swaps are curve points that flow only to
 * {@code g10_core_price}, quotes, RFQs and trades.
 */
public final class Instrument {
    public final String id;
    public final String ccy;
    public final int ccyIndex;        // index into CurveEngine's currency array
    public final String product;      // IRS | OIS | GOVT | FUTURE
    public final String benchmark;    // SOFR | ESTR | SONIA | TONA | "" for govt/future
    public final String tenor;        // label: 1M..30Y
    public final double tenorYears;   // numeric tenor (the real sort/bucket key)
    public final String tenorBucket;  // 0-1Y | 1-3Y | 3-7Y | 7-15Y | 15-30Y
    public final String priceSpace;   // rate | price
    public final double tickSize;
    public final double coupon;       // bond/future coupon (decimal, e.g. 0.04); 0 for swaps
    public final boolean hedge;       // true => liquid depth instrument (market_data)
    public final int rank;            // 1 = most liquid .. 10 = least (drives weighting)

    /** Seed sensitivity: DV01 per 1 unit of notional, computed from the seed curve. */
    public double dv01PerUnit;

    public Instrument(String id, String ccy, int ccyIndex, String product, String benchmark,
                      String tenor, double tenorYears, String priceSpace, double tickSize,
                      double coupon, boolean hedge, int rank) {
        this.id = id;
        this.ccy = ccy;
        this.ccyIndex = ccyIndex;
        this.product = product;
        this.benchmark = benchmark;
        this.tenor = tenor;
        this.tenorYears = tenorYears;
        this.tenorBucket = bucketOf(tenorYears);
        this.priceSpace = priceSpace;
        this.tickSize = tickSize;
        this.coupon = coupon;
        this.hedge = hedge;
        this.rank = rank;
    }

    public boolean isRate() {
        return "rate".equals(priceSpace);
    }

    /** Key-rate style buckets used by g10_pos_risk grouping. */
    public static String bucketOf(double years) {
        if (years <= 1.0) {
            return "0-1Y";
        }
        if (years <= 3.0) {
            return "1-3Y";
        }
        if (years <= 7.0) {
            return "3-7Y";
        }
        if (years <= 15.0) {
            return "7-15Y";
        }
        return "15-30Y";
    }
}
