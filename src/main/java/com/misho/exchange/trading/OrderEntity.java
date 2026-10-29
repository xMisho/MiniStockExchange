package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderStatus;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.user.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders", schema = "exchange")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_symbol", nullable = false, updatable = false)
    private StockEntity stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, nullable = false, updatable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 6, nullable = false, updatable = false)
    private OrderType orderType;

    @Column(name = "limit_price", precision = 19, scale = 4)
    private BigDecimal limitPrice;

    @Column(name = "original_quantity", nullable = false, updatable = false)
    private int originalQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private int remainingQuantity;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private OrderStatus status;

    @Column(name = "priority_sequence", nullable = false)
    private long prioritySequence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected OrderEntity() { }

    public OrderEntity(UserEntity user, StockEntity stock, OrderSide side, OrderType orderType,
                       BigDecimal limitPrice, int quantity, long prioritySequence, Instant createdAt) {
        if (user == null || user.getId() == null || stock == null || stock.getSymbol() == null) {
            throw new IllegalArgumentException("A persisted user and stock are required.");
        }
        if (side == null || orderType == null || quantity <= 0 || prioritySequence <= 0) {
            throw new IllegalArgumentException("Side, type, positive quantity, and priority sequence are required.");
        }
        this.user = user;
        this.stock = stock;
        this.side = side;
        this.orderType = orderType;
        this.limitPrice = validatePrice(limitPrice);
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.filledQuantity = 0;
        this.status = OrderStatus.OPEN;
        this.prioritySequence = prioritySequence;
        this.createdAt = PersistenceValues.timestamp(createdAt);
        this.updatedAt = this.createdAt;
    }

    public void updateState(BigDecimal price, int remaining, int filled, OrderStatus status,
                            long prioritySequence, Instant updatedAt) {
        if (this.status == OrderStatus.FILLED || this.status == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Terminal orders cannot change state.");
        }
        BigDecimal validatedPrice = validatePrice(price);
        Instant timestamp = PersistenceValues.timestamp(updatedAt);
        if (status == null || remaining < 0 || filled < this.filledQuantity
                || prioritySequence < this.prioritySequence || timestamp.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("Invalid order state or stale snapshot.");
        }
        boolean valid = switch (status) {
            case OPEN -> remaining > 0 && filled == 0;
            case PARTIALLY_FILLED -> remaining > 0 && filled > 0;
            case FILLED -> remaining == 0 && filled > 0;
            case CANCELLED -> remaining > 0;
        };
        if (!valid) {
            throw new IllegalArgumentException("Status must match remaining and filled quantities.");
        }
        boolean changedPrice = orderType == OrderType.LIMIT && limitPrice.compareTo(validatedPrice) != 0;
        if ((changedPrice || remaining > remainingQuantity) && prioritySequence == this.prioritySequence) {
            throw new IllegalArgumentException("Price changes and quantity increases require new priority.");
        }
        this.limitPrice = validatedPrice;
        this.remainingQuantity = remaining;
        this.filledQuantity = filled;
        this.status = status;
        this.prioritySequence = prioritySequence;
        this.updatedAt = timestamp;
    }

    private BigDecimal validatePrice(BigDecimal price) {
        if (orderType == OrderType.MARKET) {
            if (price != null) {
                throw new IllegalArgumentException("Market orders must not have a limit price.");
            }
            return null;
        }
        return PersistenceValues.price(price);
    }

    public Long getId() { return id; }
    public UserEntity getUser() { return user; }
    public StockEntity getStock() { return stock; }
    public OrderSide getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public int getOriginalQuantity() { return originalQuantity; }
    public int getRemainingQuantity() { return remainingQuantity; }
    public int getFilledQuantity() { return filledQuantity; }
    public OrderStatus getStatus() { return status; }
    public long getPrioritySequence() { return prioritySequence; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
