package engine;

/**
 * Represents the input data required to submit a new order.
 *
 * @param orderId  unique identifier of the order
 * @param side     order side (BUY or SELL)
 * @param price    limit price of the order
 * @param quantity quantity to be traded
 */
public record NewOrder(
                long orderId,
                Side side,
                long price,
                long quantity) {
}