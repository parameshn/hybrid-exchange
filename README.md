# Hybrid Exchange

A hybrid trading venue in Java: a price-time priority **order book** and a constant-product **AMM pool** side by side, with a **router** that fills each incoming order from whichever source is cheaper.

The order book comes from [aeron-matching-engine](https://github.com/parameshn/aeron-matching-engine). It is copied in unchanged, except that `bestBid()` and `bestAsk()` are now `public` so the router can read the top of the book.

## Why

An order book gives sharp prices when it is liquid but can be empty. An AMM always quotes a price but moves it with every trade. A hybrid venue uses both, so an order gets the best price available at each step.

## Design

```
                 NewOrder
                    |
              +-----v-----+
              |  Router   |   compares best book price with the pool's marginal price
              +--+-----+--+
                 |     |
        +--------v-+ +-v---------+
        | OrderBook| |   Pool    |
        | (engine) | |   (amm)   |
        +----------+ +-----------+
```

| Module | What it does |
|---|---|
| `engine` | Order book with price-time priority (from aeron-matching-engine) |
| `amm` | Constant-product pool, `x * y = k`, integer math, fee in basis points |
| `router` | Splits an order between the book and the pool |

The book and the pool know nothing about each other. Only the router touches both.

### Routing rules

Orders are immediate-or-cancel. Anything that cannot be filled within the limit price is dropped, and the router never leaves an order resting in the book.

1. **Cheapest source first.** For a buy, compare the book's best ask with the pool's marginal buy price. For a sell, compare the best bid with the pool's marginal sell price.
2. **Tie goes to the book.** Resting orders were there first, and a fixed rule keeps results deterministic.
3. **The pool never trades past the next-best price.** It fills up to the book's best price (or the order's limit if the book has nothing within it), found by binary search. If it cannot move without passing that price, the book fills the next chunk.
4. **Average price respects the limit.** A pool fill is skipped if rounding would push its average price past the limit.

### AMM details

- All values are `long` integers. Reserve products can exceed `long`, so they use `BigInteger`, and results are narrowed with `longValueExact`, which throws instead of wrapping.
- Rounding always favours the pool: the quote paid is rounded up and the quote received is rounded down.
- After every swap the pool checks that `k` did not decrease.
- Marginal prices are compared by cross-multiplication, so there is no division and no floating point.

## Tests

| Test class | What it checks |
|---|---|
| `ConstantProductPoolTest` | Swap results against reference values computed independently, `k` never decreases over 60,000 random swaps, round trips never profit, no overflow at large reserves, price-limit search is maximal |
| `HybridRouterTest` | Book-before-pool ordering, tie-break, splitting across both sources, limit handling, validation, hybrid fills never worse than pool-only, and a randomized check over 200 scenarios: quantities add up, no fill breaks its limit, `k` never decreases, no order left resting |
| determinism test | The same inputs give identical fills, pool state and book state across 50 scenarios |

```
gradle test
```

Requires JDK 21 and Gradle.

## Performance

Rough, single-thread, 2-core sandbox: about **340,000 orders/sec** (around 3 µs per order) over 1M mixed buy and sell orders against a book of about 400 resting orders and one pool. This is a plain timing loop with warm-up, not a JMH benchmark, so treat it as an order of magnitude.

## Limits

- Single pool, constant-product only. No concentrated liquidity or multiple pools.
- Limit-style immediate-or-cancel orders only. No resting router orders.
- A simulation, not a deployed protocol. It does not touch a blockchain.
- Because quantities are whole units, the pool's marginal price can end slightly below the book's price when the book takes over, by less than one unit's price impact.

## Roadmap

- `v1`: AMM pool with tests
- `v2`: router with tests
- `v3`: JMH benchmarks, a feed of orders from the SBE codecs in aeron-matching-engine
