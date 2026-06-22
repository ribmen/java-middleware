package com.victor.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victor.Gateway;
import com.victor.WorkerComponent;
import com.victor.middleware.client.TcpClient;
import com.victor.middleware.protocol.CommunicationType;

/**
 * In-process end-to-end test for Phase 3 of the
 * {@code majestic-crafting-valley} refactor plan.
 *
 * <p>Boots a real {@link Gateway} and a real {@link WorkerComponent}
 * on free ports inside the same JVM, drives a {@code WRITE}/{@code READ}
 * round-trip through the gateway using the production
 * {@link TcpClient}, and asserts the expected responses. Replaces the
 * Bash-smoke-test approach the previous session attempted — that path
 * is blocked by the documented tool-environment quirk of long-lived
 * JVMs receiving SIGTERM at tool-call boundary (see memory
 * {@code feedback-bash-background-jvm-sigterm.md}).</p>
 *
 * <p>What this exercises:</p>
 * <ul>
 *     <li>The constructor-injection seam added to {@code BaseComponent}
 *         in Phase 3 (b): {@code new WorkerComponent(...)} still works
 *         because the public 4-arg constructor delegates through the
 *         factory.</li>
 *     <li>Dead-code removal in Phase 3 (a): the worker's
 *         {@code clientToGateway} field is gone, and registration +
 *         heartbeat still succeed end-to-end through {@code BaseComponent}'s
 *         own client.</li>
 *     <li>The TCP transport on the gateway side: framing, parser, and
 *         round-robin routing through {@code GatewayRegistry}.</li>
 *     <li>The TCP transport on the worker side: the worker's
 *         {@code RequestHandler} parses {@code "WRITE|key|value"} and
 *         delegates to {@code KVStore.write} / {@code KVStore.read}.</li>
 * </ul>
 *
 * <p>What this does NOT exercise: the new
 * {@code com.victor.middleware.invoker.Dispatcher} (Phase 3 c) is
 * covered by its own unit tests in
 * {@code middleware-victor/.../invoker/DispatcherTest.java}; it is
 * deliberately not wired into {@code Gateway} or {@code WorkerComponent}
 * yet, so it has no production-path presence to verify here.</p>
 *
 * <p>Design note: the gateway has no public {@code stop()} method (a
 * known gap flagged in plan §6's Phase 2 notes), so this test does not
 * tear down the gateway instance between methods. All assertions live
 * in a single test method so the in-process setup is paid for exactly
 * once per JVM. The remaining threads exit when the JVM terminates at
 * the end of {@code mvn test}.</p>
 */
class Phase3EndToEndTest {

    private Gateway gateway;
    private WorkerComponent worker;
    private TcpClient client;
    private int gatewayPort;
    private int workerPort;

    @BeforeEach
    void setUp() throws Exception {
        // Free ports: ask the OS, hold nothing, hand them off to the components.
        gatewayPort = pickFreePort();
        workerPort  = pickFreePort();

        gateway = new Gateway(gatewayPort, CommunicationType.TCP);
        worker  = new WorkerComponent(workerPort, "127.0.0.1", gatewayPort, CommunicationType.TCP);
        client  = new TcpClient();

        gateway.start();
        // Worker.start() performs the synchronous REGISTER handshake against
        // the gateway. By the time it returns, the worker is in the
        // GatewayRegistry as HEALTHY (the default status on construction).
        worker.start();
    }

    @AfterEach
    void tearDown() {
        // Stop the worker (which has a stop() method, since it extends
        // BaseComponent). The gateway's server is left running — see
        // class-level Javadoc — because Gateway has no public stop().
        // The JVM will reclaim the accept loop when mvn test exits.
        if (worker != null) worker.stop();
    }

    /**
     * WRITE then READ through the gateway → expected write and read payloads.
     * Then a multi-overwrite sequence confirming the latest value wins.
     *
     * <p>All assertions live in one method so the gateway/worker setup is
     * paid for only once (the gateway cannot be cleanly torn down between
     * methods, see class Javadoc).</p>
     */
    @Test
    void writeReadAndOverwriteEndToEnd() throws Exception {
        String writeReply = client.send("127.0.0.1", gatewayPort, "WRITE|smoke-key|smoke-value");
        assertTrue(writeReply.contains("smoke-key"),
                "WRITE reply should mention the key, got: " + writeReply);
        assertTrue(writeReply.contains("smoke-value"),
                "WRITE reply should mention the value, got: " + writeReply);

        String readReply = client.send("127.0.0.1", gatewayPort, "READ|smoke-key");
        assertTrue(readReply.contains("smoke-value"),
                "READ reply should echo the value, got: " + readReply);

        // Overwrite the same key three times; the latest value must win.
        client.send("127.0.0.1", gatewayPort, "WRITE|k|v1");
        client.send("127.0.0.1", gatewayPort, "WRITE|k|v2");
        client.send("127.0.0.1", gatewayPort, "WRITE|k|v3");

        String overwriteRead = client.send("127.0.0.1", gatewayPort, "READ|k");
        assertTrue(overwriteRead.contains("v3"),
                "READ after 3 overwrites should return the last value, got: " + overwriteRead);
        assertFalse(overwriteRead.contains("v1"),
                "READ after overwrite should NOT return a stale value, got: " + overwriteRead);
    }

    /** Helpers ---------------------------------------------------------------- */

    /** Ask the kernel for a free TCP port and release it before returning. */
    private static int pickFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
