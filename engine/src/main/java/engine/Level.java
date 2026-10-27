package engine;

import java.util.ArrayDeque;

/** Stores orders at the same price in FIFO order. */
final class Level {

    private final ArrayDeque<Order> orders;

    Level() {
        orders = new ArrayDeque<>();
    }

    /** Adds an order to the end of the queue. */
    void add(Order order) {
        orders.addLast(order);
    }

    /** Returns the oldest order without removing it. */
    Order peek() {
        return orders.peekFirst();
    }

    /** Removes and returns the oldest order. */
    Order pollFirst() {
        return orders.pollFirst();
    }

    /** Removes and returns the given order. */
    boolean remove(Order o) {
        return orders.remove(o);
    }

    /** Returns whether the level contains no orders. */
    boolean isEmpty() {
        return orders.isEmpty();
    }

    /** Returns the number of orders at this price level. */
    int size() {
        return orders.size();
    }
}