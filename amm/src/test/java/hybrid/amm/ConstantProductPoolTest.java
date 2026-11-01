package hybrid.amm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ConstantProductPoolTest {

    // Reference values computed independently in Python.
    @Test
    void buyMatchesReferenceValue() {
        var pool = new ConstantProductPool(1_000_000, 2_000_000_000L, 30);
        assertEquals(2_008_027L, pool.swapBuyBase(1_000));
        assertEquals(999_000, pool.baseReserve());
        assertEquals(2_002_008_027L, pool.quoteReserve());
    }

    @Test
    void sellMatchesReferenceValue() {
        var pool = new ConstantProductPool(1_000_000, 2_000_000_000L, 30);
        assertEquals(1_992_013L, pool.swapSellBase(1_000));
        assertEquals(1_001_000, pool.baseReserve());
        assertEquals(2_000_000_000L - 1_992_013L, pool.quoteReserve());
    }

    @Test
    void zeroFeeBuyMatchesReferenceValue() {
        var pool = new ConstantProductPool(1_000_000, 2_000_000_000L, 0);
        assertEquals(2_002_003L, pool.swapBuyBase(1_000));
    }

    @Test
    void invariantNeverDecreasesOverRandomSwaps() {
        for (int fee : new int[] { 0, 30, 100 }) {
            var pool = new ConstantProductPool(5_000_000, 9_000_000_000L, fee);
            var rnd = new Random(42);
            BigInteger k = pool.k();
            for (int i = 0; i < 20_000; i++) {
                if (rnd.nextBoolean()) {
                    long max = Math.max(1, pool.baseReserve() / 50);
                    pool.swapBuyBase(1 + rnd.nextInt((int) Math.min(max, Integer.MAX_VALUE)));
                } else {
                    long max = Math.max(1, pool.baseReserve() / 50);
                    pool.swapSellBase(1 + rnd.nextInt((int) Math.min(max, Integer.MAX_VALUE)));
                }
                BigInteger now = pool.k();
                assertTrue(now.compareTo(k) >= 0, "k decreased at step " + i + " fee " + fee);
                k = now;
            }
        }
    }

    @Test
    void roundTripNeverProfits() {
        var pool = new ConstantProductPool(1_000_000, 2_000_000_000L, 0);
        long paid = pool.swapBuyBase(10_000);
        long back = pool.swapSellBase(10_000);
        assertTrue(back <= paid, "round trip must not profit: paid=" + paid + " back=" + back);
    }

    @Test
    void sameSequenceGivesSameState() {
        var a = new ConstantProductPool(1_000_000, 2_000_000_000L, 30);
        var b = new ConstantProductPool(1_000_000, 2_000_000_000L, 30);
        var ra = new Random(7);
        var rb = new Random(7);
        for (int i = 0; i < 5_000; i++) {
            long qa = 1 + ra.nextInt(2_000);
            long qb = 1 + rb.nextInt(2_000);
            if (i % 2 == 0) {
                assertEquals(a.swapBuyBase(qa), b.swapBuyBase(qb));
            } else {
                assertEquals(a.swapSellBase(qa), b.swapSellBase(qb));
            }
        }
        assertEquals(a.baseReserve(), b.baseReserve());
        assertEquals(a.quoteReserve(), b.quoteReserve());
    }

    @Test
    void largeReservesDoNotOverflow() {
        // k here is far above Long.MAX_VALUE; BigInteger keeps the math exact.
        var pool = new ConstantProductPool(4_000_000_000_000L, 8_000_000_000_000_000L, 30);
        BigInteger k = pool.k();
        pool.swapBuyBase(1_000_000_000L);
        assertTrue(pool.k().compareTo(k) >= 0);
    }

    @Test
    void cannotDrainPool() {
        var pool = new ConstantProductPool(1_000, 1_000_000, 30);
        assertThrows(IllegalArgumentException.class, () -> pool.swapBuyBase(1_000));
        assertThrows(IllegalArgumentException.class, () -> pool.swapBuyBase(0));
        assertThrows(IllegalArgumentException.class, () -> pool.swapSellBase(0));
    }

    @Test
    void rejectsBadConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ConstantProductPool(0, 10, 30));
        assertThrows(IllegalArgumentException.class, () -> new ConstantProductPool(10, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new ConstantProductPool(10, 10, 10_000));
        assertThrows(IllegalArgumentException.class, () -> new ConstantProductPool(10, 10, -1));
    }

    @Test
    void marginalPriceComparisons() {
        // price 2000, fee 0: buy price == 2000, sell price == 2000
        var pool = new ConstantProductPool(1_000_000, 2_000_000_000L, 0);
        assertEquals(0, pool.compareAskTo(2_000));
        assertTrue(pool.compareAskTo(2_001) < 0);
        assertTrue(pool.compareAskTo(1_999) > 0);
        assertEquals(0, pool.compareBidTo(2_000));
        // with a fee the pool asks more and bids less than the mid price
        var fee = new ConstantProductPool(1_000_000, 2_000_000_000L, 100);
        assertTrue(fee.compareAskTo(2_000) > 0);
        assertTrue(fee.compareBidTo(2_000) < 0);
    }

    @Test
    void maxBuyWithinPriceIsMaximalAndRespectsPrice() {
        var rnd = new Random(11);
        for (int i = 0; i < 300; i++) {
            long rb = 100_000 + rnd.nextInt(5_000_000);
            long rq = rb * (500 + rnd.nextInt(3_000));
            int fee = rnd.nextInt(100);
            var pool = new ConstantProductPool(rb, rq, fee);
            long mid = rq / rb;
            long limit = mid + 1 + rnd.nextInt(200);
            long maxBase = 1 + rnd.nextInt(2_000_000);
            long x = pool.maxBuyWithinPrice(limit, maxBase);
            if (x > 0) {
                var after = new ConstantProductPool(rb, rq, fee);
                after.swapBuyBase(x);
                assertTrue(after.compareAskTo(limit) <= 0, "price exceeded after buying " + x);
            }
            if (x < Math.min(maxBase, rb - 1)) {
                var over = new ConstantProductPool(rb, rq, fee);
                over.swapBuyBase(x + 1);
                assertTrue(over.compareAskTo(limit) > 0, "x not maximal: " + x);
            }
        }
    }

    @Test
    void maxSellWithinPriceIsMaximalAndRespectsPrice() {
        var rnd = new Random(13);
        for (int i = 0; i < 300; i++) {
            long rb = 100_000 + rnd.nextInt(5_000_000);
            long rq = rb * (500 + rnd.nextInt(3_000));
            int fee = rnd.nextInt(100);
            var pool = new ConstantProductPool(rb, rq, fee);
            long mid = rq / rb;
            long limit = Math.max(1, mid - 1 - rnd.nextInt(200));
            long maxBase = 1 + rnd.nextInt(2_000_000);
            long y = pool.maxSellWithinPrice(limit, maxBase);
            if (y > 0) {
                var after = new ConstantProductPool(rb, rq, fee);
                after.swapSellBase(y);
                assertTrue(after.compareBidTo(limit) >= 0, "price fell below limit after selling " + y);
            }
            if (y < maxBase) {
                var over = new ConstantProductPool(rb, rq, fee);
                over.swapSellBase(y + 1);
                assertTrue(over.compareBidTo(limit) < 0, "y not maximal: " + y);
            }
        }
    }
}
