package com.victor.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victor.Gateway;
import com.victor.WorkerComponent;
import com.victor.middleware.client.TcpClient;
import com.victor.middleware.factory.CommunicationFactory;
import com.victor.middleware.marshaller.JsonMarshaller;
import com.victor.middleware.marshaller.MarshalledClient;
import com.victor.middleware.marshaller.MarshalledServer;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.protocol.Message;

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
    private MarshalledClient client;
    private int gatewayPort;
    private int workerPort;
    private int serverPort;

    @BeforeEach
    void setUp() throws Exception {
        // Free ports: ask the OS, hold nothing, hand them off to the components.
        gatewayPort = pickFreePort();
        workerPort  = pickFreePort();

        gateway = new Gateway(gatewayPort, CommunicationType.TCP);
        worker  = new WorkerComponent(workerPort, "127.0.0.1", gatewayPort, CommunicationType.TCP);
        client  = new MarshalledClient(new TcpClient(), new JsonMarshaller());
        serverPort = pickFreePort();

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
        Message writeReply = client.send("127.0.0.1", gatewayPort,
                new Message(Command.WRITE, List.of("smoke-key", "smoke-value")));
        assertEquals(Command.OK, writeReply.command());
        assertTrue(writeReply.args().get(0).contains("smoke-key"),
                "WRITE reply should mention the key, got: " + writeReply.args().get(0));
        assertTrue(writeReply.args().get(0).contains("smoke-value"),
                "WRITE reply should mention the value, got: " + writeReply.args().get(0));

        Message readReply = client.send("127.0.0.1", gatewayPort,
                new Message(Command.READ, List.of("smoke-key")));
        assertEquals(Command.OK, readReply.command());
        assertTrue(readReply.args().get(0).contains("smoke-value"),
                "READ reply should echo the value, got: " + readReply.args().get(0));

        // Overwrite the same key three times; the latest value must win.
        client.send("127.0.0.1", gatewayPort, new Message(Command.WRITE, List.of("k", "v1")));
        client.send("127.0.0.1", gatewayPort, new Message(Command.WRITE, List.of("k", "v2")));
        client.send("127.0.0.1", gatewayPort, new Message(Command.WRITE, List.of("k", "v3")));

        Message overwriteRead = client.send("127.0.0.1", gatewayPort,
                new Message(Command.READ, List.of("k")));
        assertEquals(Command.OK, overwriteRead.command());
        assertTrue(overwriteRead.args().get(0).contains("v3"),
                "READ after 3 overwrites should return the last value, got: " + overwriteRead.args().get(0));
        assertFalse(overwriteRead.args().get(0).contains("v1"),
                "READ after overwrite should NOT return a stale value, got: " + overwriteRead.args().get(0));
    }

    /**
     * Phase 5B boundary test: exercise the {@link MarshalledServer} /
     * {@link MarshalledClient} decorator layer end-to-end on a real TCP
     * transport — without going through the kvstore gateway. This
     * proves the JSON envelope path composes with the existing
     * {@code TcpServer}/{@code TcpClient} stack independently of the
     * gateway's router.
     */
    @Test
    void marshallerDecoratorRoundTripsOnTcpTransport() throws Exception {
        // Bring up a MarshalledServer over a raw TcpServer with a
        // tiny handler, and a MarshalledClient over a TcpClient.
        MarshalledServer marshalledServer = new MarshalledServer(
                CommunicationFactory.createServer(CommunicationType.TCP),
                new JsonMarshaller());
        marshalledServer.startTyped(serverPort, msg ->
                new Message(Command.OK, msg.args()));

        MarshalledClient marshalledClient = new MarshalledClient(
                CommunicationFactory.createClient(CommunicationType.TCP),
                new JsonMarshaller());

        Message resp = marshalledClient.send("127.0.0.1", serverPort,
                new Message(Command.WRITE, List.of("alpha", "beta")));
        assertEquals(Command.OK, resp.command());
        assertEquals(List.of("alpha", "beta"), resp.args());

        marshalledServer.stop();
    }

    /** Helpers ---------------------------------------------------------------- */

    /** Ask the kernel for a free TCP port and release it before returning. */
    private static int pickFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
