package com.agent00code.loop;

import com.agent00code.config.AgentConfig;
import com.agent00code.loop.LoopEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Background scheduled runner that executes the agentic loop at a configurable
 * interval (loop_interval_seconds from AGENTS.md loop-config).
 * <p>
 * Connected WebSocket subscribers on /ws/agent receive real-time events.
 */
@Component
public class ScheduledLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledLoopRunner.class);

    private final AgentLoop agentLoop;
    private final AgentConfig agentConfig;
    private final SystemPromptBuilder systemPromptBuilder;
    private final Set<BlockingQueue<LoopEvent>> subscribers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-loop-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile int runCount = 0;

    public ScheduledLoopRunner(AgentLoop agentLoop,
                               AgentConfig agentConfig,
                               SystemPromptBuilder systemPromptBuilder) {
        this.agentLoop = agentLoop;
        this.agentConfig = agentConfig;
        this.systemPromptBuilder = systemPromptBuilder;
    }

    @PostConstruct
    public void start() {
        String initialPrompt = agentConfig.loop().initialPrompt();
        int intervalSeconds = agentConfig.loop().loopIntervalSeconds();

        if (initialPrompt == null || initialPrompt.isBlank()) {
            log.info("No initial_prompt configured — background loop not started");
            return;
        }

        log.info("Background scheduled loop starting (interval={}s)", intervalSeconds);

        // Run immediately, then schedule repeats if interval > 0
        scheduler.submit(this::executeRun);

        if (intervalSeconds > 0) {
            scheduler.scheduleWithFixedDelay(
                    this::executeRun,
                    intervalSeconds,
                    intervalSeconds,
                    TimeUnit.SECONDS
            );
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public void addSubscriber(BlockingQueue<LoopEvent> queue) {
        subscribers.add(queue);
        log.info("Agent observer connected ({} subscriber(s))", subscribers.size());
    }

    public void removeSubscriber(BlockingQueue<LoopEvent> queue) {
        subscribers.remove(queue);
        log.info("Agent observer disconnected ({} subscriber(s))", subscribers.size());
    }

    /**
     * Triggers an immediate agent run. Can be called from HTTP endpoints or the scheduler.
     */
    public void triggerRun() {
        scheduler.submit(this::executeRun);
    }

    private void executeRun() {
        runCount++;
        int currentRun = runCount;

        log.info("Starting scheduled run #{}", currentRun);

        broadcast(new LoopEvent(
                EventType.ITERATION,
                Map.of("run", currentRun, "source", "scheduled", "iteration", 0),
                0));

        BlockingQueue<LoopEvent> eventQueue = new LinkedBlockingQueue<>();

        // Forward events from the loop to all subscribers with run metadata
        Thread forwarder = Thread.ofVirtual().name("event-forwarder-" + currentRun).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LoopEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (event == null) continue;

                    Map<String, Object> taggedData = new java.util.HashMap<>(event.data());
                    taggedData.put("run", currentRun);
                    taggedData.put("source", "scheduled");
                    LoopEvent tagged = new LoopEvent(event.type(), taggedData, event.iteration());

                    broadcast(tagged);

                    if (event.type() == EventType.FINAL_ANSWER || event.type() == EventType.ERROR) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        try {
            String systemPrompt = systemPromptBuilder.build();
            LoopResult result = agentLoop.run(
                    systemPrompt,
                    agentConfig.loop().initialPrompt(),
                    agentConfig.loop().maxIterations(),
                    eventQueue);

            log.info("Scheduled run #{} complete after {} iteration(s)", currentRun, result.iterations());
        } catch (Exception e) {
            log.error("Unhandled error in scheduled run #{}", currentRun, e);
            broadcast(new LoopEvent(
                    EventType.ERROR,
                    Map.of("error", e.getMessage(), "run", currentRun, "source", "scheduled"),
                    0));
        } finally {
            forwarder.interrupt();
        }
    }

    private void broadcast(LoopEvent event) {
        for (BlockingQueue<LoopEvent> q : subscribers) {
            q.offer(event);
        }
    }
}
