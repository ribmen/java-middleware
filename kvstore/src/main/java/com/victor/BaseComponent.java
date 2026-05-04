package com.victor;

import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.victor.client.UdpClient;


public abstract class BaseComponent {

    protected final int componentPort;
    protected final String gatewayHost;
    protected final int gatewayPort;
    protected final CommunicationType type;
    protected final ComponentType componentType;

    private ComponentServer server;
    private final ComponentClient clientToGateway;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    private static final int HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final int HEARTBEAT_TIMEOUT_MS = 2000;
    private static final int MAX_HEARTBEAT_RETRIES = 2;


    public BaseComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type, ComponentType componentType) {
        this.componentPort = componentPort;
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.type = type;
        this.componentType = componentType;
        this.clientToGateway = CommunicationFactory.createClient(type);
    }

    public void start() throws Exception {

        server = CommunicationFactory.createServer(type);
        server.start(componentPort , getRequestHandler());

        System.out.printf("[%s] Servidor iniciado na porta %d\n", componentType, componentPort);

        registerWithGateway();
        startHeartbeat();
    }


    private void registerWithGateway() throws Exception {
        String myIp = InetAddress.getLocalHost().getHostAddress();
        String request = String.format("REGISTER|%s|%d|%s", myIp, componentPort, componentType);
        
        // Retry na registração
        Exception lastException = null;
        for (int i = 0; i < 3; i++) {
            try {
                String response = clientToGateway.send(gatewayHost, gatewayPort, request);
                System.out.printf("[%s] Resposta do registro no Gateway: %s\n", componentType, response);
                return; // Sucesso
            } catch (Exception e) {
                lastException = e;
                System.err.printf("[%s] Falha na registração (tentativa %d): %s\n", componentType, i+1, e.getMessage());
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
                String request = String.format("HEARTBEAT|%s|%d|%s", myIp, componentPort, componentType);
                
                // Usa timeout customizado se disponível
                if (clientToGateway instanceof UdpClient) {
                    ((UdpClient) clientToGateway).sendWithRetry(gatewayHost, gatewayPort, request, HEARTBEAT_TIMEOUT_MS, 1);
                } else {
                    clientToGateway.send(gatewayHost, gatewayPort, request);
                }
                
                // sucesso
                if (i > 1) {
                    System.out.printf("[%s] Heartbeat recuperado na tentativa %d\n", componentType, i);
                }
                return;
                
            } catch (Exception e) {
                if (i == MAX_HEARTBEAT_RETRIES) {
                    System.err.printf("[%s] Falha ao enviar heartbeat após %d tentativas: %s\n", 
                                     componentType, MAX_HEARTBEAT_RETRIES, e.getMessage());
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