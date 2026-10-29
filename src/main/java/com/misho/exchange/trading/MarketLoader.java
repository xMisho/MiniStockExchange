package com.misho.exchange.trading;

import com.misho.exchange.MatchingEngine;
import com.misho.exchange.OrderSnapshot;
import com.misho.exchange.Stock;
import com.misho.exchange.Trade;
import com.misho.exchange.stock.StockRepository;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class MarketLoader {

    private final StockRepository stocks;
    private final OrderRepository orders;
    private final TradeRepository trades;

    MarketLoader(StockRepository stocks, OrderRepository orders, TradeRepository trades) {
        this.stocks = stocks;
        this.orders = orders;
        this.trades = trades;
    }

    LoadedMarket load(String symbol) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recovery requires a database transaction.");
        }
        var stock = stocks.findById(symbol)
                .orElseThrow(() -> new UnknownMarketException());
        var rows = orders.findByStock_SymbolOrderByPrioritySequenceAsc(symbol);
        var history = trades.findByStock_SymbolOrderByIdAsc(symbol);
        var snapshots = rows.stream().map(row -> new OrderSnapshot(row.getId(), row.getSide(),
                row.getOrderType(), row.getLimitPrice(), row.getOriginalQuantity(), row.getRemainingQuantity(),
                row.getFilledQuantity(), row.getStatus(), row.getCreatedAt().toEpochMilli())).toList();
        var savedTrades = history.stream().map(row -> new Trade(row.getBuyOrder().getId(),
                row.getSellOrder().getId(), row.getExecutionPrice(), row.getQuantity(),
                row.getExecutedAt().toEpochMilli())).toList();
        MatchingEngine engine = MatchingEngine.restore(new Stock(stock.getSymbol(),
                stock.getCompanyName(), stock.getReferencePrice()), snapshots, savedTrades);
        LinkedHashMap<Long, OrderEntity> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        return new LoadedMarket(stock, byId, engine, savedTrades.size());
    }
}
