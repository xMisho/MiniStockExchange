package com.misho.exchange.trading;

import com.misho.exchange.OrderStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    @Transactional
    @Query(value = "SELECT nextval('exchange.order_priority_sequence')", nativeQuery = true)
    long nextPrioritySequence();

    @EntityGraph(attributePaths = {"user", "stock"})
    List<OrderEntity> findByStock_SymbolOrderByPrioritySequenceAsc(String symbol);

    @EntityGraph(attributePaths = {"user", "stock"})
    Page<OrderEntity> findByUser_Id(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "stock"})
    Page<OrderEntity> findByUser_IdAndStatus(Long userId, OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "stock"})
    @Query("""
            select o from OrderEntity o where o.stock.symbol = :symbol
            and o.orderType = com.misho.exchange.OrderType.LIMIT
            and o.status in (com.misho.exchange.OrderStatus.OPEN, com.misho.exchange.OrderStatus.PARTIALLY_FILLED)
            order by o.prioritySequence
            """)
    List<OrderEntity> findRestingOrders(@Param("symbol") String symbol);

    @Query("""
            select o from OrderEntity o where o.orderType = com.misho.exchange.OrderType.MARKET
            and o.status in (com.misho.exchange.OrderStatus.OPEN, com.misho.exchange.OrderStatus.PARTIALLY_FILLED)
            order by o.id
            """)
    List<OrderEntity> findUnfinishedMarketOrders();

    @Query("select o.id from OrderEntity o where o.stock.symbol = :symbol order by o.id")
    List<Long> findUsedOrderIds(@Param("symbol") String symbol);
}
