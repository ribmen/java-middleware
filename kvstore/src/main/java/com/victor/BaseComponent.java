package com.victor;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.factory.CommunicationFactory;
import com.victor.middleware.marshaller.JsonMarshaller;
import com.victor.middleware.marshaller.MarshalledClient;
import com.victor.middleware.marshaller.MarshalledServer;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.ComponentClient;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.TypedRequestHandler;

/**
 * Base class for any component that registers with a gateway and
 * services typed requests. Workers (and any future component) extend
 * this; they only have to provide a {@link TypedRequestHandler} via
 * {@link #getRequestHandler()}.
 *
 * <p>Phase 5D pipe-removal: the base speaks typed {@link Message} on
 * every cross-JVM boundary. Registration and heartbeats go out through
 * a {@link MarshalledClient} (sends typed {@code Message} → JSON
 * envelope). Inbound requests come in through a {@link MarshalledServer}
 * that decodes the envelope before dispatching to the subclass's
 * {@link TypedRequestHandler}. The {@code "REGISTER|IP|PORT"} pipe
 * string is gone — the wire is exclusively JSON.</p>
 */
public abstract class BaseComponent {

    private static final String NODE_LABEL = "WORKER";

    protected final int componentPort;
    protected final String gatewayHost;
    protected final int gatewayPort;
    protected final CommunicationType type;

    private MarshalledServer server;
    private final MarshalledClient clientToGateway;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    private static final int HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final int MAX_HEARTBEAT_RETRIES = 2;


    /**
     * Production constructor: looks up the client and server from
     * {@link CommunicationFactory}, then wraps both in the typed
     * marshalling decorators. Kept for the production call sites
     * ({@link WorkerComponent} etc.) that don't care about wiring.
     */
    public BaseComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type) {
        this(componentPort, gatewayHost, gatewayPort, type,
             CommunicationFactory.createClient(type),
             CommunicationFactory.createServer(type));
    }

    /**
     * Test-seam constructor: accepts pre-built raw client + server. The
     * base still wraps both in marshalling decorators so the subclass's
     * handler always sees the typed contract regardless of caller.
     */
    BaseComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type,
                  ComponentClient client, ComponentServer server) {
        this.componentPort = componentPort;
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.type = type;
        JsonMarshaller marshaller = new JsonMarshaller();
        this.clientToGateway = new MarshalledClient(client, marshaller);
        this.server = new MarshalledServer(server, marshaller);
    }

    public void start() throws Exception {
        if (server == null) {
            server = new MarshalledServer(
                    CommunicationFactory.createServer(type), new JsonMarshaller());
        }
        server.startTyped(componentPort, getRequestHandler());

        System.out.printf("[%s] Servidor iniciado na porta %d\n", NODE_LABEL, componentPort);

        registerWithGateway();
        startHeartbeat();
    }


    private void registerWithGateway() throws Exception {
        String myIp = InetAddress.getLocalHost().getHostAddress();
        Message request = new Message(Command.REGISTER,
                List.of(myIp, Integer.toString(componentPort)));

        // Retry no registro
        Exception lastException = null;
        for (int i = 0; i < 3; i++) {
            try {
                Message response = clientToGateway.send(gatewayHost, gatewayPort, request);
                System.out.printf("[%s] Resposta do registro no Gateway: %s\n", NODE_LABEL,
                        response.args().isEmpty() ? "(empty)" : response.args().get(0));
                return; // Sucesso
            } catch (Exception e) {
                lastException = e;
                System.err.printf("[%s] Falha no registro (tentativa %d): %s\n", NODE_LABEL, i+1, e.getMessage());
                if (i < 2) Thread.sleep(1000);
            }
        }
        throw lastException;
    }



    private void startHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            sendHeartbeatWithRetry();
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void sendHeartbeatWithRetry() {
        for (int i = 1; i <= MAX_HEARTBEAT_RETRIES; i++) {
            try {
                String myIp;
                try {
                    myIp = InetAddress.getLocalHost().getHostAddress();
                } catch (java.net.UnknownHostException uhe) {
                    System.err.printf("[%s] Não foi possível resolver IP local: %s%n", NODE_LABEL, uhe.getMessage());
                    return;
                }
                Message request = new Message(Command.HEARTBEAT,
                        List.of(myIp, Integer.toString(componentPort)));
                clientToGateway.send(gatewayHost, gatewayPort, request);

                // fez retry
                if (i > 1) {
                    System.out.printf("[%s] Heartbeat recuperado na tentativa %d\n", NODE_LABEL, i);
                }
                return;

            } catch (Exception e) {
                if (i == MAX_HEARTBEAT_RETRIES) {
                    System.err.printf("[%s] Falha ao enviar heartbeat após %d tentativas: %s\n",
                                     NODE_LABEL, MAX_HEARTBEAT_RETRIES, e.getMessage());
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** @return the typed handler that processes incoming requests on {@link #componentPort}. */
    protected abstract TypedRequestHandler getRequestHandler();

    public void stop() {
        heartbeatScheduler.shutdownNow();
        if (server != null) server.stop();
    }
}
