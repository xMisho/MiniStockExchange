package com.misho.exchange.paper;

import com.misho.exchange.externalmarket.ExternalMarketService;
import com.misho.exchange.externalmarket.ExternalMarketException;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/paper")
public class PaperTradingController {
    public record Submit(String symbol,String side,String type,Integer quantity,BigDecimal limitPrice) { }
    public record Amend(BigDecimal limitPrice,Integer quantity) { }
    private final PaperTradingService paper;
    private final UserService users;
    public PaperTradingController(PaperTradingService paper,UserService users){this.paper=paper;this.users=users;}
    private long actor(Principal p){return users.getProfile(p.getName()).orElseThrow().id();}
    @GetMapping("/instruments") public List<Map<String,String>> instruments(){return ExternalMarketService.INSTRUMENTS.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.of("symbol",e.getKey(),"companyName",e.getValue(),"currency","USD")).toList();}
    @PostMapping("/orders") public ResponseEntity<PaperTradingService.Order> submit(Principal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Submit r){
        if(r==null || r.quantity()==null)throw new IllegalArgumentException("Order fields are required.");
        var o=paper.submit(actor(p),r.symbol(),r.side(),r.type(),r.quantity(),r.limitPrice(),key);
        return ResponseEntity.created(java.net.URI.create("/api/paper/orders/"+o.id())).body(o);
    }
    @GetMapping("/orders") public PaperTradingService.Page orders(Principal p,@RequestParam(defaultValue="0") int page){return paper.orders(actor(p),page);}
    @GetMapping("/orders/{id}") public PaperTradingService.Order order(Principal p,@PathVariable long id){return paper.order(actor(p),id);}
    @PatchMapping("/orders/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void amend(Principal p,@PathVariable long id,@RequestBody Amend r){if(r==null || r.quantity()==null)throw new IllegalArgumentException("Price and quantity are required.");paper.amend(actor(p),id,r.limitPrice(),r.quantity());}
    @DeleteMapping("/orders/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void cancel(Principal p,@PathVariable long id){paper.cancel(actor(p),id);}
    @GetMapping("/fills") public List<PaperTradingService.Order> fills(Principal p,@RequestParam String symbol){return paper.fills(actor(p),symbol);}
    @GetMapping("/portfolio") public PaperTradingService.Portfolio portfolio(Principal p){return paper.portfolio(actor(p));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    @ExceptionHandler(PaperTradingService.Conflict.class) public ResponseEntity<?> conflict(PaperTradingService.Conflict e){return ResponseEntity.status(409).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("error","Paper order not found."));}
    @ExceptionHandler(ExternalMarketException.class) public ResponseEntity<?> unavailable(ExternalMarketException e){return ResponseEntity.status(503).body(Map.of("error",e.getMessage(),"retrySafe",false));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<?> database(){return ResponseEntity.status(503).body(Map.of("error","Paper trading is unavailable. Check My Orders before retrying with the same request key.","retrySafe",false));}
}
