package com.misho.exchange.trading;

import com.misho.exchange.MatchingEngine;
import com.misho.exchange.stock.StockEntity;
import java.util.LinkedHashMap;

record LoadedMarket(StockEntity stock, LinkedHashMap<Long, OrderEntity> orders,
                    MatchingEngine engine, int previousTradeCount) { }
