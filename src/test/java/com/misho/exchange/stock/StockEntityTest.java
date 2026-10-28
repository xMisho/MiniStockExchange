package com.misho.exchange.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StockEntityTest {

    @Test
    void normalizesMetadataAndPreservesExactPrice() {
        StockEntity stock = new StockEntity(" aapl ", " Apple Inc. ", new BigDecimal("250.1234"));
        assertEquals("AAPL", stock.getSymbol());
        assertEquals("Apple Inc.", stock.getCompanyName());
        assertEquals(new BigDecimal("250.1234"), stock.getReferencePrice());
        assertEquals(new BigDecimal("250.1000"),
                new StockEntity("AAPL", "Apple", new BigDecimal("250.10000")).getReferencePrice());
    }

    @Test
    void rejectsInvalidSymbolsNamesAndPrices() {
        for (String symbol : new String[]{null, "", " ", "A".repeat(21)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new StockEntity(symbol, "Apple", BigDecimal.TEN));
        }
        for (String name : new String[]{null, "", " ", "A".repeat(256)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new StockEntity("AAPL", name, BigDecimal.TEN));
        }
        for (BigDecimal price : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1"),
                new BigDecimal("0.00001"), new BigDecimal("1000000000000000")}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new StockEntity("AAPL", "Apple", price));
        }
        assertEquals(new BigDecimal("999999999999999.9999"),
                new StockEntity("MAX", "Maximum", new BigDecimal("999999999999999.9999"))
                        .getReferencePrice());
    }

    @Test
    void symbolNormalizationIsIndependentOfTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("IBM", new StockEntity("ibm", "IBM", BigDecimal.TEN).getSymbol());
        } finally {
            Locale.setDefault(previous);
        }
    }
}
