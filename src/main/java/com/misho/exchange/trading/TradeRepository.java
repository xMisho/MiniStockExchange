package com.misho.exchange.trading;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    @EntityGraph(attributePaths = {"stock", "buyOrder", "sellOrder"})
    List<TradeEntity> findByStock_SymbolOrderByIdAsc(String symbol);

    @EntityGraph(attributePaths = "stock")
    Page<TradeEntity> findByStock_Symbol(String symbol, Pageable pageable);

    @Query("""
            select t from TradeEntity t
            where t.buyOrder.user.id = :userId or t.sellOrder.user.id = :userId
            order by t.id
            """)
    List<TradeEntity> findUserTrades(@Param("userId") Long userId);
}
