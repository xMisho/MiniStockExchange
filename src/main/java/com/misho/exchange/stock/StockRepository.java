package com.misho.exchange.stock;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<StockEntity, String> {

    List<StockEntity> findAllByOrderBySymbolAsc();
}
