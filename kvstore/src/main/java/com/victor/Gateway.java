package com.victor;

import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.victor.middleware.exceptions.NoAvailableNodeException;
import com.victor.middleware.factory.CommunicationFactory;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.protocol.MessageParser;
import com.victor.middleware.registry.GatewayRegistry;
import com.victor.middleware.registry.ServiceRecord;
import com.victor.middleware.spi.ComponentClient;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.RequestHandler;

/**
 * Gateway process: receives client requests, routes them to a healthy
 * worker via round-robin, and tracks worker liveness via heartbeats.
 *
 * <p>Phase 2 refactor: the in-line service registry
 * ({@code Map<String, ComponentInfo>}, heartbeat sweeper with a known
 * timing bug, round-robin) was extracted into
 * {@link GatewayRegistry}. The bug — original
 * {@code scheduleAtFixedRate(task, port, port, TimeUnit.SECONDS)} where
 * the listening port (e.g. 9000) was used as both the initial delay and
 * the period, producing a 2.5h sweep interval — is fixed in
 * {@link GatewayRegistry#HEARTBEAT_INITIAL_DELAY_SECONDS} and
 * {@link GatewayRegistry#HEARTBEAT_PERIOD_SECONDS}.</p>
 *
 * <p>Wire parsing now goes through {@link MessageParser#parse(String)}
 * instead of the duplicated {@code split("\\|")} call that lived in this
 * file and in {@code WorkerComponent}.</p>
 */
public class Gateway {

    private final int port;
    private final CommunicationType type;
    private ComponentServer server;

    private final GatewayRegistry registry = new GatewayRegistry();
    private final ComponentClient internalClient;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public Gateway(int port, CommunicationType type) {
        this.port = port;
        this.type = type;
        this.internalClient = CommunicationFactory.createClient(type);
    }

    public void start() throws Exception {
        this.server = CommunicationFactory.createServer(type);

        RequestHandler handler = (rawRequest) -> {
            System.out.println("Gateway recebeu: " + rawRequest);
            Message msg = MessageParser.parse(rawRequest);
            Command command = msg.command();

            switch (command) {
                case REGISTER:
                    return handleRegistration(msg);
                case HEARTBEAT:
                    return handleHeartbeat(msg);
                case WRITE:
                case READ:
                    return routeRequest(command, msg);
                default:
                    return "ERRO: Comando desconhecido: " + command.wireForm();
            }
        };

        server.start(port, handler);
        startHeartbeatCheck();
        System.out.println("GATEWAY ROUTER iniciado no padrão " + type + " na porta " + port);
    }

    /**
     * Kept for compatibility with the pre-Phase-2 test surface, but
     * delegates the actual sweep to {@link GatewayRegistry#failureDetectorSweep()}.
     * The Phase 2 fix lives in {@link GatewayRegistry}'s constructor — this
     * local scheduler is now effectively dead and will be removed in a
     * follow-up once the registry's own sweeper is verified.
     */
    private void startHeartbeatCheck() {
        // Heartbeat sweeper is now owned by GatewayRegistry (constructed above).
        // This local scheduler is preserved as a no-op seam for the next
        // refactor pass.
        heartbeatScheduler.shutdown();
    }

    private String handleRegistration(Message msg) {
        try {
            if (msg.args().size() < 2) {
                return "ERRO: Formato de registro inválido. Esperado REGISTER|IP|PORTA.";
            }
            InetAddress address = InetAddress.getByName(msg.args().get(0));
            int componentPort = Integer.parseInt(msg.args().get(1));
            registry.register(address, componentPort);
            System.out.printf("[GATEWAY] NODES - Workers: %d%n", registry.size());
            return "SUCESSO: Registrado.";
        } catch (Exception e) {
            return "ERRO: Falha ao registrar componente. Formato inválido.";
        }
    }

    private String handleHeartbeat(Message msg) {
        try {
            if (msg.args().size() < 2) {
                return "ERRO: Formato de heartbeat inválido. Esperado HEARTBEAT|IP|PORTA.";
            }
            InetAddress address = InetAddress.getByName(msg.args().get(0));
            int componentPort = Integer.parseInt(msg.args().get(1));
            boolean ok = registry.heartbeat(address, componentPort);
            if (ok) {
                return "OK";
            }
            return "[GATEWAY] Componente não registrado para atualizar HEARTBEAT";
        } catch (Exception e) {
            return "ERRO: Formato de heartbeat inválido.";
        }
    }

    private String routeRequest(Command command, Message msg) {
        try {
            String payload = msg.args().isEmpty() ? "" : String.join(MessageParser.DELIM, msg.args());
            return switch (command) {
                case WRITE -> routeWrite(payload);
                case READ -> routeRead(payload);
                default -> "ERRO: Comando desconhecido: " + command.wireForm();
            };
        } catch (Exception e) {
            System.err.println("[Gateway] Erro de comunicação ao rotear '" + command + "': " + e.getMessage());
            return "ERRO: " + e.toString();
        }
    }

    private String routeWrite(String payload) {
        ServiceRecord target;
        try {
            target = registry.findHealthy();
        } catch (NoAvailableNodeException e) {
            return "[GATEWAY] ERRO: Nenhum WORKER disponivel para WRITE";
        }

        String request = "WRITE" + (payload.isEmpty() ? "" : "|" + payload);
        try {
            String response = sendWithTimeout(target, request, 100000);
            System.out.println("[GATEWAY] WRITE roteado para " + target.getId());
            return response;
        } catch (Exception e) {
            System.err.println("[GATEWAY] WRITE falhou em " + target.getId() + ": " + e.getMessage());
            return "ERRO: WRITE_FAILED em " + target.getId() + ": " + e.getMessage();
        }
    }

    private String routeRead(String payload) {
        ServiceRecord target;
        try {
            target = registry.findHealthy();
        } catch (NoAvailableNodeException e) {
            return "[GATEWAY] ERRO: Nenhum WORKER disponivel para READ";
        }

        String request = "READ" + (payload.isEmpty() ? "" : "|" + payload);
        try {
            String response = sendWithTimeout(target, request, 100000);
            System.out.println("[GATEWAY] READ roteado para " + target.getId());
            return response;
        } catch (Exception e) {
            System.err.println("[GATEWAY] READ falhou em " + target.getId() + ": " + e.getMessage());
            return "ERRO: READ_FAILED em " + target.getId() + ": " + e.getMessage();
        }
    }

    private String sendWithTimeout(ServiceRecord target, String request, int timeout) throws Exception {
        int maxtentativas = 3;
        Exception lastException = null;

        for (int i = 0; i < maxtentativas; i++) {
            try {
                return internalClient.send(target.getAddress().getHostAddress(), target.getPort(), request);
            } catch (Exception e) {
                lastException = e;
                System.err.println("[Gateway] Tentativa " + (i + 1) + " falhou para " + target.getId() + ": " + e.getMessage());

                if (i < maxtentativas - 1) {
                    Thread.sleep(1000);
                }
            }
        }
        throw lastException;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Lembre-se: java Gateway <porta> <TCP|HTTP>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        CommunicationType type = CommunicationType.valueOf(args[1].toUpperCase());
        new Gateway(port, type).start();
    }
}
