package com.victor.middleware.registry;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.victor.middleware.exceptions.NoAvailableNodeException;

/**
 * In-process service registry used by the gateway to track workers
 * (or any other component type) and pick one for each routed request.
 *
 * <p>Adapts the reference project's
 * {@code br.imd.ufrn.application.gateway.GatewayRegistry} with three
 * concrete improvements:</p>
 *
 * <ol>
 *     <li><b>Heartbeat bug fix</b> — the reference used
 *         {@code scheduleAtFixedRate(task, port, port, TimeUnit.SECONDS)},
 *         passing the gateway's listening port (e.g. 9000) as both the
 *         initial delay and the period. The first sweep therefore happened
 *         ~2.5h after startup, and recurred every 2.5h — making heartbeats
 *         effectively useless. We use two named constants,
 *         {@link #HEARTBEAT_INITIAL_DELAY_SECONDS} and
 *         {@link #HEARTBEAT_PERIOD_SECONDS}, both set to 10s to match the
 *         worker's own heartbeat cadence.</li>
 *     <li><b>{@link ScheduledExecutorService} instead of {@code while(true)}</b> —
 *         the reference spins a raw thread in an unbounded loop. We use
 *         a single-thread scheduled executor with proper
 *         {@code shutdown()} hooks, so a registry that is no longer needed
 *         can stop its background sweeper deterministically.</li>
 *     <li><b>Typed {@link NoAvailableNodeException}</b> — the reference
 *         returns {@code null} from {@code getNextService()} on empty
 *         registries, forcing every caller to null-check. We throw a
 *         checked exception that callers can either let propagate or
 *         catch and translate into a wire-form error.</li>
 * </ol>
 *
 * <p>Thread-safety: the underlying map is a {@link ConcurrentHashMap},
 * round-robin uses an {@link AtomicInteger}, and the sweeper mutates
 * records through volatile fields — no external synchronization is
 * required by callers.</p>
 */
public class GatewayRegistry {

    /**
     * How often the failure detector sweeps. Must be {@code <=} the
     * worker-side {@code HEARTBEAT_INTERVAL_SECONDS} for stale detection
     * to be timely.
     */
    public static final int HEARTBEAT_PERIOD_SECONDS = 10;

    /**
     * Initial delay before the first sweep. Kept distinct from the period
     * so they can be tuned independently — for example, the period can
     * stay at 10s while the initial delay is bumped to 30s to give workers
     * a moment to register after the gateway comes up.
     */
    public static final int HEARTBEAT_INITIAL_DELAY_SECONDS = 10;

    /**
     * Maximum age of a heartbeat before a record is marked
     * {@link NodeStatus#STALE}. Three periods is a reasonable default:
     * tolerate one missed heartbeat but flag after two.
     */
    public static final int STALE_AFTER_MILLIS = 3 * HEARTBEAT_PERIOD_SECONDS * 1000;

    private final ConcurrentHashMap<String, ServiceRecord> servicesTable = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final ScheduledExecutorService sweeper;

    /** Start a registry with the background sweeper running. */
    public GatewayRegistry() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-registry-sweeper");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Constructor for tests: inject a custom executor (or {@code null} to disable). */
    public GatewayRegistry(ScheduledExecutorService sweeper) {
        this.sweeper = sweeper;
        if (sweeper != null) {
            sweeper.scheduleAtFixedRate(
                    this::failureDetectorSweep,
                    HEARTBEAT_INITIAL_DELAY_SECONDS,
                    HEARTBEAT_PERIOD_SECONDS,
                    TimeUnit.SECONDS);
        }
    }

    /**
     * Register a node, or refresh an existing registration's heartbeat.
     * Idempotent — calling twice with the same {@code (host, port)} does
     * not create a duplicate.
     */
    public void register(InetAddress address, int port) {
        String key = address.getHostAddress() + ":" + port;
        ServiceRecord existing = servicesTable.get(key);
        if (existing == null) {
            // putIfAbsent so a concurrent register doesn't clobber a fresh
            // record created in the race window
            ServiceRecord fresh = new ServiceRecord(address, port);
            ServiceRecord prior = servicesTable.putIfAbsent(key, fresh);
            if (prior == null) {
                System.out.println("[GATEWAY-REGISTRY] Registrado: " + key);
            } else {
                prior.refreshHeartbeat();
            }
        } else {
            existing.refreshHeartbeat();
        }
    }

    /**
     * Refresh the heartbeat for an already-registered node. Returns
     * {@code true} if the node was found and refreshed, {@code false}
     * if no record matches.
     */
    public boolean heartbeat(InetAddress address, int port) {
        ServiceRecord r = servicesTable.get(address.getHostAddress() + ":" + port);
        if (r == null) {
            return false;
        }
        r.refreshHeartbeat();
        return true;
    }

    /**
     * @return the count of records in the table, regardless of status.
     *         For diagnostics / "NODES" log lines.
     */
    public int size() {
        return servicesTable.size();
    }

    /**
     * Pick a healthy node using round-robin. Throws
     * {@link NoAvailableNodeException} when no healthy nodes exist —
     * callers should translate this into a wire-form error rather than
     * null-check.
     */
    public ServiceRecord findHealthy() throws NoAvailableNodeException {
        List<ServiceRecord> healthy = new ArrayList<>();
        for (ServiceRecord r : servicesTable.values()) {
            if (r.status() == NodeStatus.HEALTHY) {
                healthy.add(r);
            }
        }
        if (healthy.isEmpty()) {
            throw new NoAvailableNodeException(
                    "Nenhum nó saudável registrado. Total no registry: " + servicesTable.size());
        }
        int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), healthy.size());
        return healthy.get(idx);
    }

    /**
     * One pass of the failure detector. Public for tests; do not call
     * directly in production — the scheduled executor already drives it.
     */
    public void failureDetectorSweep() {
        long now = System.currentTimeMillis();
        for (ServiceRecord r : servicesTable.values()) {
            if (r.status() == NodeStatus.HEALTHY
                    && (now - r.lastHeartbeatMillis()) > STALE_AFTER_MILLIS) {
                System.out.println("[GATEWAY-REGISTRY] Marcando como STALE: " + r.getId());
                r.markStale();
            }
        }
    }

    /** Stop the background sweeper. No-op if it was never started. */
    public void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }
}
