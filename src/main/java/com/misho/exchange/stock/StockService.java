package com.misho.exchange.stock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StockService {

    private final StockRepository repository;
    private final EntityManager entityManager;

    public StockService(StockRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional
    public StockResponse registerStock(String symbol, String companyName, BigDecimal referencePrice) {
        StockEntity stock = new StockEntity(symbol, companyName, referencePrice);
        if (repository.existsById(stock.getSymbol())) {
            throw new StockAlreadyExistsException();
        }
        try {
            entityManager.persist(stock);
            entityManager.flush();
        } catch (PersistenceException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new StockAlreadyExistsException();
                }
            }
            throw exception;
        }
        return StockResponse.from(stock);
    }

    public List<StockResponse> getStocks() {
        return repository.findAllByOrderBySymbolAsc().stream().map(StockResponse::from).toList();
    }

    public Optional<StockResponse> getStock(String symbol) {
        return repository.findById(StockEntity.normalizeSymbol(symbol)).map(StockResponse::from);
    }
}
