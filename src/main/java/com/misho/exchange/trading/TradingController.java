package com.misho.exchange.trading;

import com.misho.exchange.OrderStatus;
import com.misho.exchange.user.UserService;
import java.net.URI;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ResponseEntity<OrderResponse> submit(Principal principal, @PathVariable String symbol,
                                               @RequestBody SubmitOrderRequest request) {
        if (request == null || request.quantity() == null) {
            throw new IllegalArgumentException("Order quantity is required.");
        }
        PersistedOrder order = trading.submitOrder(actor(principal), symbol, request.side(), request.type(),
                request.limitPrice(), request.quantity());
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(OrderResponse.from(order));
    }

    @GetMapping("/orders/me")
    public OrderHistoryResponse mine(Principal principal,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size,
                                     @RequestParam(required = false) OrderStatus status) {
        return OrderHistoryResponse.from(trading.getOrders(actor(principal), page, size, status));
    }

    @GetMapping("/orders/{id}")
    public OrderResponse get(Principal principal, @PathVariable long id) {
        return OrderResponse.from(trading.getOrder(actor(principal), id));
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

}
