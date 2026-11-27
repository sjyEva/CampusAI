package edu.campusai.rag;

import edu.campusai.auth.DemoAccounts;
import edu.campusai.auth.DemoAccounts.Account;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Genuine provider streaming: provisional deltas are replaced by a validated done result. */
@RestController
@RequestMapping("/campusai/courses/{courseId}/rag")
public class RagStreamingController {
    private final DemoAccounts accounts;
    private final RagService rag;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "rag-stream"); thread.setDaemon(true); return thread; });
    private final Set<ModelGateway.StreamCancellation> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "rag-stream-heartbeat"); thread.setDaemon(true); return thread;
    });

    public RagStreamingController(DemoAccounts accounts, RagService rag) { this.accounts = accounts; this.rag = rag; }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> ask(Authentication principal, @PathVariable long courseId,
                                          @RequestBody RagController.Question request) {
        if (request == null || request.question() == null || !request.question().isTextual()) {
            throw new RagException(400, "question 必须是非空字符串");
        }
        Account account = accounts.find(principal.getName());
        String question = request.question().textValue();
        rag.validateStream(account, courseId, question);
        long timeout = rag.capabilities(account, courseId).answerTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(timeout);
        ModelGateway.StreamCancellation cancellation = new ModelGateway.StreamCancellation();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Future<?>> worker = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable cancel = () -> {
            cancellation.cancel();
            Future<?> future = worker.get(); if (future != null) future.cancel(true);
            ScheduledFuture<?> beat = heartbeat.get(); if (beat != null) beat.cancel(false);
            active.remove(cancellation);
        };
        emitter.onCompletion(() -> { complete.set(true); cancel.run(); });
        emitter.onError(error -> { complete.set(true); cancel.run(); });
        emitter.onTimeout(() -> {
            if (complete.compareAndSet(false, true)) {
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("code", 504, "message", "请求超时，请重新提问。"), MediaType.APPLICATION_JSON)); }
                catch (IOException | IllegalStateException ignored) { }
                emitter.complete();
            }
            cancel.run();
        });
        active.add(cancellation);
        heartbeat.set(heartbeats.scheduleAtFixedRate(() -> {
            if (complete.get() || cancellation.isClientCancelled()) return;
            try { emitter.send(SseEmitter.event().comment("keepalive")); }
            catch (IOException | IllegalStateException exception) { complete.set(true); cancel.run(); emitter.complete(); }
        }, 2, 2, TimeUnit.SECONDS));
        try {
            Future<?> submitted = workers.submit(() -> {
                try {
                    var answer = rag.askStream(account, courseId, question,
                            stage -> send(emitter, complete, cancellation, "status", Map.of("stage", stage)),
                            text -> send(emitter, complete, cancellation, "delta", Map.of("text", text)), cancellation);
                    send(emitter, complete, cancellation, "done", answer);
                } catch (StreamClosed ignored) {
                    // Client departure is not an application error; stop the provider read immediately.
                } catch (RagException exception) {
                    try { send(emitter, complete, cancellation, "error", Map.of("code", exception.code(), "message", exception.getMessage())); }
                    catch (StreamClosed ignored) { }
                } catch (Exception exception) {
                    try { send(emitter, complete, cancellation, "error", Map.of("code", 500, "message", "问答暂时不可用，请重新提问。")); }
                    catch (StreamClosed ignored) { }
                } finally {
                    if (complete.compareAndSet(false, true)) emitter.complete();
                    ScheduledFuture<?> beat = heartbeat.get(); if (beat != null) beat.cancel(false);
                    cancellation.cancel(); active.remove(cancellation);
                }
            });
            worker.set(submitted);
            if (complete.get()) submitted.cancel(true);
        } catch (RejectedExecutionException exception) {
            heartbeat.get().cancel(false);
            active.remove(cancellation); cancellation.cancel();
            throw new RagException(429, "当前问答请求较多，请稍后重试。");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
                .header("X-Accel-Buffering", "no").body(emitter);
    }

    private static void send(SseEmitter emitter, AtomicBoolean complete, ModelGateway.StreamCancellation cancellation,
                             String event, Object data) {
        if (complete.get() || cancellation.isClientCancelled()) throw new StreamClosed();
        try { emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON)); }
        catch (IOException | IllegalStateException exception) { cancellation.cancel(); throw new StreamClosed(); }
    }

    @PreDestroy
    public void stop() { active.forEach(ModelGateway.StreamCancellation::cancel); workers.shutdownNow(); heartbeats.shutdownNow(); }
    private static final class StreamClosed extends RuntimeException { }
}
