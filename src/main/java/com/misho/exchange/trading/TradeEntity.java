package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import com.misho.exchange.stock.StockEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trades", schema = "exchange")
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_symbol", nullable = false, updatable = false)
    private StockEntity stock;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buy_order_id", nullable = false, updatable = false)
    private OrderEntity buyOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sell_order_id", nullable = false, updatable = false)
    private OrderEntity sellOrder;

    @Column(name = "execution_price", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal executionPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    protected TradeEntity() { }

    public TradeEntity(OrderEntity buyOrder, OrderEntity sellOrder, BigDecimal executionPrice,
                       int quantity, Instant executedAt) {
        if (buyOrder == null || sellOrder == null || buyOrder.getId() == null || sellOrder.getId() == null
                || buyOrder.getId().equals(sellOrder.getId())) {
            throw new IllegalArgumentException("Two distinct persisted orders are required.");
        }
        if (buyOrder.getSide() != OrderSide.BUY || sellOrder.getSide() != OrderSide.SELL
                || !buyOrder.getStock().getSymbol().equals(sellOrder.getStock().getSymbol())) {
            throw new IllegalArgumentException("Trade orders must be BUY and SELL for the same stock.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Trade quantity must be positive.");
        }
        this.stock = buyOrder.getStock();
        this.buyOrder = buyOrder;
        this.sellOrder = sellOrder;
        this.executionPrice = PersistenceValues.price(executionPrice);
        this.quantity = quantity;
        this.executedAt = PersistenceValues.timestamp(executedAt);
    }

    public Long getId() { return id; }
    public StockEntity getStock() { return stock; }
    public OrderEntity getBuyOrder() { return buyOrder; }
    public OrderEntity getSellOrder() { return sellOrder; }
    public BigDecimal getExecutionPrice() { return executionPrice; }
    public int getQuantity() { return quantity; }
    public Instant getExecutedAt() { return executedAt; }
}
