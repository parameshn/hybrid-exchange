package hybrid.amm;

import java.math.BigInteger;

/**
 * Constant-product AMM (x * y = k) over a base/quote pair, using integer math only.
 *
 * <p>Prices are quote units per one base unit. The pool charges a fee in basis
 * points on the input side. All rounding favours the pool, so the product of the
 * reserves never decreases after a swap.
 *
 * <p>Intermediate products can exceed {@code long}, so they are computed with
 * {@link BigInteger}. Results are narrowed back with {@code longValueExact}, which
 * throws instead of silently wrapping.
 */
public final class ConstantProductPool {

    private static final BigInteger BPS = BigInteger.valueOf(10_000);

    private long baseReserve;
    private long quoteReserve;
    private final int feeBps;
    private final BigInteger keep; // 10_000 - fee

    public ConstantProductPool(long baseReserve, long quoteReserve, int feeBps) {
        if (baseReserve <= 0 || quoteReserve <= 0)
            throw new IllegalArgumentException("reserves must be positive");
        if (feeBps < 0 || feeBps >= 10_000)
            throw new IllegalArgumentException("feeBps must be in [0, 10000)");
        this.baseReserve = baseReserve;
        this.quoteReserve = quoteReserve;
        this.feeBps = feeBps;
        this.keep = BigInteger.valueOf(10_000L - feeBps);
    }

    public long baseReserve() {
        return baseReserve;
    }

    public long quoteReserve() {
        return quoteReserve;
    }

    public int feeBps() {
        return feeBps;
    }

    /** The invariant k = baseReserve * quoteReserve. */
    public BigInteger k() {
        return big(baseReserve).multiply(big(quoteReserve));
    }

    // ---------------------------------------------------------------- quoting

    /**
     * Quote needed to buy {@code baseOut} base from the pool (rounded up, fee included).
     */
    public long quoteInForBaseOut(long baseOut) {
        if (baseOut <= 0 || baseOut >= baseReserve)
            throw new IllegalArgumentException("baseOut must be in (0, baseReserve)");
        return quoteIn(baseOut, baseReserve, quoteReserve);
    }

    /**
     * Quote received for selling {@code baseIn} base to the pool (rounded down, fee included).
     */
    public long quoteOutForBaseIn(long baseIn) {
        if (baseIn <= 0)
            throw new IllegalArgumentException("baseIn must be positive");
        return quoteOut(baseIn, baseReserve, quoteReserve);
    }

    // ------------------------------------------------------------------ swaps

    /** Buy {@code baseOut} base from the pool. Returns the quote paid. */
    public long swapBuyBase(long baseOut) {
        long paid = quoteInForBaseOut(baseOut);
        BigInteger before = k();
        long newBase = baseReserve - baseOut;
        long newQuote = Math.addExact(quoteReserve, paid);
        if (big(newBase).multiply(big(newQuote)).compareTo(before) < 0)
            throw new IllegalStateException("invariant violated");
        baseReserve = newBase;
        quoteReserve = newQuote;
        return paid;
    }

    /** Sell {@code baseIn} base to the pool. Returns the quote received. */
    public long swapSellBase(long baseIn) {
        long received = quoteOutForBaseIn(baseIn);
        BigInteger before = k();
        long newBase = Math.addExact(baseReserve, baseIn);
        long newQuote = quoteReserve - received;
        if (big(newBase).multiply(big(newQuote)).compareTo(before) < 0)
            throw new IllegalStateException("invariant violated");
        baseReserve = newBase;
        quoteReserve = newQuote;
        return received;
    }

    // ------------------------------------------------------- marginal prices

    /**
     * Sign of (marginal buy price - price). Negative means the pool sells base
     * cheaper than {@code price} for an infinitesimal trade.
     * Marginal buy price = quote / (base * (1 - fee)).
     */
    public int compareAskTo(long price) {
        return compareAsk(baseReserve, quoteReserve, price);
    }

    /**
     * Sign of (marginal sell price - price). Positive means the pool pays more
     * than {@code price} for an infinitesimal trade.
     * Marginal sell price = quote * (1 - fee) / base.
     */
    public int compareBidTo(long price) {
        return compareBid(baseReserve, quoteReserve, price);
    }

    /**
     * Largest {@code x <= maxBase} such that, after buying {@code x} base, the pool's
     * marginal buy price is still at or below {@code price}. Returns 0 if none.
     * Binary search; the marginal price is monotonic in {@code x}.
     */
    public long maxBuyWithinPrice(long price, long maxBase) {
        long lo = 0;
        long hi = Math.min(maxBase, baseReserve - 1);
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            long paid = quoteIn(mid, baseReserve, quoteReserve);
            long nb = baseReserve - mid;
            long nq = Math.addExact(quoteReserve, paid);
            if (compareAsk(nb, nq, price) <= 0)
                lo = mid;
            else
                hi = mid - 1;
        }
        return lo;
    }

    /**
     * Largest {@code y <= maxBase} such that, after selling {@code y} base, the pool's
     * marginal sell price is still at or above {@code price}. Returns 0 if none.
     */
    public long maxSellWithinPrice(long price, long maxBase) {
        long lo = 0;
        long hi = Math.min(maxBase, Long.MAX_VALUE - baseReserve);
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            long out = quoteOut(mid, baseReserve, quoteReserve);
            long nb = baseReserve + mid;
            long nq = quoteReserve - out;
            if (compareBid(nb, nq, price) >= 0)
                lo = mid;
            else
                hi = mid - 1;
        }
        return lo;
    }

    // ---------------------------------------------------------------- helpers

    private long quoteIn(long baseOut, long rb, long rq) {
        // floor(rq * out * 10000 / ((rb - out) * (10000 - fee))) + 1  (round up)
        BigInteger num = big(rq).multiply(big(baseOut)).multiply(BPS);
        BigInteger den = big(rb - baseOut).multiply(keep);
        return num.divide(den).add(BigInteger.ONE).longValueExact();
    }

    private long quoteOut(long baseIn, long rb, long rq) {
        // floor(in * (10000 - fee) * rq / (rb * 10000 + in * (10000 - fee)))
        BigInteger inKeep = big(baseIn).multiply(keep);
        BigInteger num = inKeep.multiply(big(rq));
        BigInteger den = big(rb).multiply(BPS).add(inKeep);
        return num.divide(den).longValueExact();
    }

    private int compareAsk(long rb, long rq, long price) {
        BigInteger lhs = big(rq).multiply(BPS);
        BigInteger rhs = big(price).multiply(big(rb)).multiply(keep);
        return lhs.compareTo(rhs);
    }

    private int compareBid(long rb, long rq, long price) {
        BigInteger lhs = big(rq).multiply(keep);
        BigInteger rhs = big(price).multiply(big(rb)).multiply(BPS);
        return lhs.compareTo(rhs);
    }

    private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }

    @Override
    public String toString() {
        return "Pool[base=" + baseReserve + ", quote=" + quoteReserve + ", feeBps=" + feeBps + "]";
    }
}
