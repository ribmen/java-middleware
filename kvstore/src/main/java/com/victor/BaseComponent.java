package com.victor;

import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.victor.middleware.factory.CommunicationFactory;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.spi.ComponentClient;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.RequestHandler;

public abstract class BaseComponent {

    private static final String NODE_LABEL = "WORKER";

    protected final int componentPort;
    protected final String gatewayHost;
    protected final int gatewayPort;
    protected final CommunicationType type;

    private ComponentServer server;
    private final ComponentClient clientToGateway;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    private static final int HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final int MAX_HEARTBEAT_RETRIES = 2;


    /**
     * Production constructor: looks up the client and server from
     * {@link CommunicationFactory}. Kept for the production call sites
     * ({@link WorkerComponent} etc.) that don't care about wiring.
     */
    public BaseComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type) {
        this(componentPort, gatewayHost, gatewayPort, type,
             CommunicationFactory.createClient(type),
             CommunicationFactory.createServer(type));
    }

    /**
     * Test-seam constructor: accepts pre-built client + server. Allows
     * tests to substitute in-memory doubles without touching the static
     * factory.
     */
    BaseComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type,
                  ComponentClient client, ComponentServer server) {
        this.componentPort = componentPort;
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.type = type;
        this.clientToGateway = client;
        this.server = server;
    }

    public void start() throws Exception {

        if (server == null) {
            server = CommunicationFactory.createServer(type);
        }
        server.start(componentPort , getRequestHandler());

        System.out.printf("[%s] Servidor iniciado na porta %d\n", NODE_LABEL, componentPort);

        registerWithGateway();
        startHeartbeat();
    }


    private void registerWithGateway() throws Exception {
        String myIp = InetAddress.getLocalHost().getHostAddress();
        String request = String.format("REGISTER|%s|%d", myIp, componentPort);

        // Retry no registro
        Exception lastException = null;
        for (int i = 0; i < 3; i++) {
            try {
                String response = clientToGateway.send(gatewayHost, gatewayPort, request);
                System.out.printf("[%s] Resposta do registro no Gateway: %s\n", NODE_LABEL, response);
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
                String myIp = InetAddress.getLocalHost().getHostAddress();
                String request = String.format("HEARTBEAT|%s|%d", myIp, componentPort);
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

    // cada componente vai implementar o handling de requs
    protected abstract RequestHandler getRequestHandler();

    public void stop() {
        heartbeatScheduler.shutdownNow();
        if (server != null) server.stop();
    }
}