package com.misho.exchange.marketdata;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Best-effort invalidation hints; consumers reload authoritative snapshots on connect/reconnect. */
@Component
public class MarketEvents {
    private static final class Client {
        final String ip;
        final SseEmitter emitter = new SseEmitter(300_000L);
        final Set<String> pending = ConcurrentHashMap.newKeySet();
        final AtomicBoolean sending = new AtomicBoolean();
        volatile boolean connected;
        volatile long heartbeat;
        Client(String ip) { this.ip = ip; }
    }
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "market-sse-heartbeat"); t.setDaemon(true); return t; });
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), r -> { var t = new Thread(r, "market-sse-send"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    public MarketEvents() { scheduler.scheduleAtFixedRate(this::flush, 1, 1, TimeUnit.SECONDS); }
    public synchronized SseEmitter subscribe(String ip) {
        if (clients.size() >= 128 || clients.stream().filter(c -> c.ip.equals(ip)).count() >= 3)
            throw new StreamLimitException();
        Client client = new Client(ip);
        clients.add(client);
        client.emitter.onCompletion(() -> clients.remove(client));
        client.emitter.onTimeout(() -> { clients.remove(client); client.emitter.complete(); });
        client.emitter.onError(e -> clients.remove(client));
        return client.emitter;
    }
    public void committed(String symbol) {
        for (Client c : clients) {
            if (c.pending.size() < 256) c.pending.add(symbol);
        }
    }
    private void flush() {
        for (Client c : clients) {
            if (!c.sending.compareAndSet(false, true)) continue;
            try { senders.execute(() -> {
                try {
                    if (!c.connected) { c.emitter.send(SseEmitter.event().name("connected").data(Map.of("refresh", true))); c.connected = true; }
                    for (String symbol : c.pending.toArray(String[]::new)) {
                        c.pending.remove(symbol);
                        c.emitter.send(SseEmitter.event().name("market").data(Map.of("symbol", symbol)));
                    }
                    if (System.nanoTime() - c.heartbeat > 15_000_000_000L) {
                        c.emitter.send(SseEmitter.event().comment("heartbeat")); c.heartbeat = System.nanoTime();
                    }
                } catch (Exception error) { clients.remove(c); c.emitter.completeWithError(error); }
                finally { c.sending.set(false); }
            }); } catch (RejectedExecutionException busy) { c.sending.set(false); }
        }
    }
    @PreDestroy public void stop() {
        scheduler.shutdownNow(); senders.shutdownNow();
        for (Client c : clients) c.emitter.complete();
        clients.clear();
    }
    public static class StreamLimitException extends RuntimeException { }
}
