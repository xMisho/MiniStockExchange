package com.misho.exchange.workspace;

import com.misho.exchange.externalmarket.AlpacaMarketClient;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    public record SymbolRequest(String symbol) { }
    public record AlertRequest(String symbol, String direction, BigDecimal target) { }
    private final WorkspaceService workspace;
    private final PortfolioService portfolio;
    private final UserService users;
    public WorkspaceController(WorkspaceService workspace, PortfolioService portfolio, UserService users) { this.workspace = workspace; this.portfolio = portfolio; this.users = users; }
    private long actor(Principal principal) {
        if (principal == null) throw new org.springframework.security.access.AccessDeniedException("Authentication required.");
        return users.getProfile(principal.getName()).orElseThrow().id();
    }
    @GetMapping("/watchlist") public List<AlpacaMarketClient.Instrument> watchlist(Principal principal) { return workspace.watchlist(actor(principal)); }
    @PostMapping("/watchlist") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(Principal principal, @RequestBody SymbolRequest request) { if (request == null) throw new IllegalArgumentException("Symbol is required."); workspace.saveWatch(actor(principal), request.symbol()); }
    @DeleteMapping("/watchlist/{symbol}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Principal principal, @PathVariable String symbol) { workspace.removeWatch(actor(principal), symbol); }
    @GetMapping("/alerts") public WorkspaceService.AlertList alerts(Principal principal) { return workspace.checkAlerts(actor(principal)); }
    @PostMapping("/alerts") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void create(Principal principal, @RequestBody AlertRequest request) { if (request == null) throw new IllegalArgumentException("Alert fields are required."); workspace.createAlert(actor(principal), request.symbol(), request.direction(), request.target()); }
    @DeleteMapping("/alerts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAlert(Principal principal, @PathVariable long id) { workspace.removeAlert(actor(principal), id); }
    @GetMapping("/portfolio") public PortfolioService.Portfolio portfolio(Principal principal) { return portfolio.portfolio(actor(principal)); }
    @ExceptionHandler(com.misho.exchange.externalmarket.ExternalMarketException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(com.misho.exchange.externalmarket.ExternalMarketException exception) { return Map.of("error", exception.getMessage(), "code", exception.code()); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException exception) { return Map.of("error", exception.getMessage()); }
    @ExceptionHandler(java.util.NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing() { return Map.of("error", "External stock not found."); }
}
