package com.misho.exchange.trading;

import com.misho.exchange.user.UserService;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TradingController {

    private final TradingService trading;
    private final UserService users;

    public TradingController(TradingService trading, UserService users) {
        this.trading = trading;
        this.users = users;
    }

    @PostMapping("/stocks/{symbol}/orders")
    public ResponseEntity<PersistedOrder> submit(Principal principal, @PathVariable String symbol,
                                               @RequestBody SubmitOrderRequest request) {
        if (request == null || request.quantity() == null) {
            throw new IllegalArgumentException("Order quantity is required.");
        }
        PersistedOrder order = trading.submitOrder(actor(principal), symbol, request.side(), request.type(),
                request.limitPrice(), request.quantity());
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping("/orders/me")
    public List<PersistedOrder> mine(Principal principal) {
        return trading.getOrders(actor(principal));
    }

    @GetMapping("/orders/{id}")
    public PersistedOrder get(Principal principal, @PathVariable long id) {
        return trading.getOrder(actor(principal), id);
    }

    @PatchMapping("/orders/{id}")
    public ResponseEntity<Void> amend(Principal principal, @PathVariable long id,
                                     @RequestBody AmendOrderRequest request) {
        long actor = actor(principal);
        requireOpen(trading.getOrder(actor, id));
        if (request == null || request.newRemainingQuantity() == null) {
            throw new IllegalArgumentException("Remaining quantity is required.");
        }
        if (!trading.amendOrder(actor, id, request.newPrice(), request.newRemainingQuantity())) {
            throw new OrderNotOpenException();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Void> cancel(Principal principal, @PathVariable long id) {
        long actor = actor(principal);
        trading.getOrder(actor, id);
        if (!trading.cancelOrder(actor, id)) { throw new OrderNotOpenException(); }
        return ResponseEntity.noContent().build();
    }

    private long actor(Principal principal) {
        if (principal == null) { throw new AccessDeniedException("Authentication required."); }
        return users.getProfile(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("User not found.")).id();
    }

    private void requireOpen(PersistedOrder order) {
        if (order.status() == com.misho.exchange.OrderStatus.FILLED
                || order.status() == com.misho.exchange.OrderStatus.CANCELLED) {
            throw new OrderNotOpenException();
        }
    }

    @ExceptionHandler({OrderNotFoundException.class, UnknownMarketException.class})
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(OrderNotOpenException.class)
    public ResponseEntity<Map<String, Object>> notOpen() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Order is no longer open."));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid order request."));
    }

    @ExceptionHandler(TradingOperationException.class)
    public ResponseEntity<Map<String, Object>> uncertainOutcome() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Trading outcome is uncertain. Check order history before retrying.",
                "code", "TRADING_OUTCOME_UNCERTAIN", "retrySafe", false));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Trading is unavailable. Check order history before retrying.", "retrySafe", false));
    }

    private static class OrderNotOpenException extends RuntimeException { }
}
