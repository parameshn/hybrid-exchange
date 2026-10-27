package engine;

/** Internal representation of an order in the order book. */
final class Order {
    final long id;
    final Side side;
    final long price; // Price in ticks
    long remaining;

    /**
     * Creates an order with the given ID, side, price, and quantity.
     */
    Order(long id, Side side, long price, long qty) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = qty;
    }
}