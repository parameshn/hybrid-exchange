package engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Coordinates order submission, matching, and cancellation.
 */
public final class OrderBook {

    private final TreeMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Level> asks = new TreeMap<>();
    private final Map<Long, Order> byId = new HashMap<>();

    /**
     * Submit a new order and return the events generated.
     * TRADE events come first; ACCEPTED is emitted only for a remainder that rests
     * in the book.
     */
    public List<Event> submit(NewOrder in) {
        List<Event> events = new ArrayList<>();

        if (in.quantity() <= 0 || byId.containsKey(in.orderId())) {
            events.add(Event.rejected(in.orderId()));
            return events;
        }

        Order incoming = new Order(in.orderId(), in.side(), in.price(), in.quantity());
        TreeMap<Long, Level> opposite = sideMap(in.side() == Side.BUY ? Side.SELL : Side.BUY);

        while (incoming.remaining > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, Level> best = opposite.firstEntry();
            long levelPrice = best.getKey();
            Level level = best.getValue();
            if (!crosses(incoming, levelPrice))
                break;

            Order resting = level.peek();
            long fillQty = Math.min(incoming.remaining, resting.remaining);

            events.add(Event.trade(incoming.id, resting.id, levelPrice, fillQty));
            incoming.remaining -= fillQty;
            resting.remaining -= fillQty;

            if (resting.remaining == 0) {
                level.pollFirst();
                byId.remove(resting.id);
                if (level.isEmpty())
                    opposite.remove(levelPrice);
            }
        }

        if (incoming.remaining > 0) {
            rest(incoming);
            events.add(Event.accepted(incoming.id, incoming.price, incoming.remaining));
        }
        return events;
    }

    /**
     * Cancel an order by its ID.
     * An unknown id and an already-filled id both return REJECTED, because filled
     * orders leave byId.
     */
    public List<Event> cancel(long orderId) {
        return removeResting(orderId)
                ? List.of(Event.cancelled(orderId))
                : List.of(Event.rejected(orderId));
    }

    /** Rest an order in the book without matching. */
    void rest(Order o) {
        if (byId.containsKey(o.id))
            throw new IllegalArgumentException("duplicate id " + o.id);
        sideMap(o.side).computeIfAbsent(o.price, k -> new Level()).add(o);
        byId.put(o.id, o);
    }

    /** Returns true if the order existed and was removed. */
    private boolean removeResting(long orderId) {
        Order o = byId.remove(orderId);
        if (o == null)
            return false;
        TreeMap<Long, Level> map = sideMap(o.side);
        Level level = map.get(o.price);
        level.remove(o);
        if (level.isEmpty())
            map.remove(o.price);
        return true;
    }

    public Optional<Long> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    public Optional<Long> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    private TreeMap<Long, Level> sideMap(Side s) {
        return s == Side.BUY ? bids : asks;
    }

    private static boolean crosses(Order incoming, long levelPrice) {
        return incoming.side == Side.BUY
                ? levelPrice <= incoming.price
                : levelPrice >= incoming.price;
    }
}