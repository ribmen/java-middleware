package com.victor;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.exceptions.NoAvailableNodeException;
import com.victor.middleware.factory.CommunicationFactory;
import com.victor.middleware.marshaller.JsonMarshaller;
import com.victor.middleware.marshaller.MarshalledClient;
import com.victor.middleware.marshaller.MarshalledServer;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.registry.GatewayRegistry;
import com.victor.middleware.registry.ServiceRecord;
import com.victor.middleware.spi.TypedRequestHandler;

/**
 * Gateway process: receives client requests, routes them to a healthy
 * worker via round-robin, and tracks worker liveness via heartbeats.
 *
 * <p>Phase 5D pipe-removal: the gateway speaks the typed
 * {@link Message} contract end-to-end. The raw {@code ComponentServer}
 * from {@link CommunicationFactory} is wrapped in a
 * {@link MarshalledServer} so {@code startTyped(port, handler)} decodes
 * each incoming JSON envelope into a typed {@code Message} before
 * dispatching and re-encodes the typed response. Outbound worker calls
 * go through a {@link MarshalledClient}. The pipe-string
 * {@code WRITE|key|value} format is gone — wire bytes are exclusively
 * the JSON envelope.</p>
 *
 * <p>Phase 2 refactor history: the in-line service registry, heartbeat
 * sweeper with a known timing bug, and round-robin were extracted into
 * {@link GatewayRegistry}. The bug
 * ({@code scheduleAtFixedRate(task, port, port, TimeUnit.SECONDS)} where
 * the listening port was used as both initial delay and period) is fixed
 * in {@link GatewayRegistry#HEARTBEAT_INITIAL_DELAY_SECONDS} and
 * {@link GatewayRegistry#HEARTBEAT_PERIOD_SECONDS}.</p>
 */
public class Gateway {

    private final int port;
    private final CommunicationType type;
    private MarshalledServer server;

    private final GatewayRegistry registry = new GatewayRegistry();
    private final MarshalledClient internalClient;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public Gateway(int port, CommunicationType type) {
        this.port = port;
        this.type = type;
        this.internalClient = new MarshalledClient(
                CommunicationFactory.createClient(type), new JsonMarshaller());
    }

    public void start() throws Exception {
        this.server = new MarshalledServer(
                CommunicationFactory.createServer(type), new JsonMarshaller());

        TypedRequestHandler handler = (request) -> {
            System.out.println("Gateway recebeu: " + request.command().wireForm()
                    + " args=" + request.args());
            Command command = request.command();

            return switch (command) {
                case REGISTER -> handleRegistration(request);
                case HEARTBEAT -> handleHeartbeat(request);
                case WRITE, READ -> routeRequest(command, request);
                default -> errorEnvelope("Comando desconhecido: " + command.wireForm());
            };
        };

        server.startTyped(port, handler);
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

    private Message handleRegistration(Message msg) {
        if (msg.args().size() < 2) {
            return errorEnvelope("Formato de registro inválido. Esperado REGISTER|IP|PORTA.");
        }
        try {
            InetAddress address = InetAddress.getByName(msg.args().get(0));
            int componentPort = Integer.parseInt(msg.args().get(1));
            registry.register(address, componentPort);
            System.out.printf("[GATEWAY] NODES - Workers: %d%n", registry.size());
            return new Message(Command.OK, List.of("SUCESSO: Registrado."));
        } catch (Exception e) {
            return errorEnvelope("Falha ao registrar componente. Formato inválido.");
        }
    }

    private Message handleHeartbeat(Message msg) {
        if (msg.args().size() < 2) {
            return errorEnvelope("Formato de heartbeat inválido. Esperado HEARTBEAT|IP|PORTA.");
        }
        try {
            InetAddress address = InetAddress.getByName(msg.args().get(0));
            int componentPort = Integer.parseInt(msg.args().get(1));
            boolean ok = registry.heartbeat(address, componentPort);
            if (ok) {
                return new Message(Command.OK, List.of("OK"));
            }
            return errorEnvelope("Componente não registrado para atualizar HEARTBEAT");
        } catch (Exception e) {
            return errorEnvelope("Formato de heartbeat inválido.");
        }
    }

    private Message routeRequest(Command command, Message msg) {
        try {
            Message response = switch (command) {
                case WRITE -> routeWrite(msg);
                case READ -> routeRead(msg);
                default -> errorEnvelope("Comando desconhecido: " + command.wireForm());
            };
            return response;
        } catch (Exception e) {
            System.err.println("[Gateway] Erro de comunicação ao rotear '" + command + "': " + e.getMessage());
            return errorEnvelope(e.toString());
        }
    }

    private Message routeWrite(Message msg) {
        ServiceRecord target;
        try {
            target = registry.findHealthy();
        } catch (NoAvailableNodeException e) {
            return errorEnvelope("Nenhum WORKER disponivel para WRITE");
        }

        try {
            Message workerResponse = sendWithTimeout(target, msg, 100000);
            System.out.println("[GATEWAY] WRITE roteado para " + target.getId());
            return workerResponse;
        } catch (Exception e) {
            System.err.println("[GATEWAY] WRITE falhou em " + target.getId() + ": " + e.getMessage());
            return errorEnvelope("WRITE_FAILED em " + target.getId() + ": " + e.getMessage());
        }
    }

    private Message routeRead(Message msg) {
        ServiceRecord target;
        try {
            target = registry.findHealthy();
        } catch (NoAvailableNodeException e) {
            return errorEnvelope("Nenhum WORKER disponivel para READ");
        }

        try {
            Message workerResponse = sendWithTimeout(target, msg, 100000);
            System.out.println("[GATEWAY] READ roteado para " + target.getId());
            return workerResponse;
        } catch (Exception e) {
            System.err.println("[GATEWAY] READ falhou em " + target.getId() + ": " + e.getMessage());
            return errorEnvelope("READ_FAILED em " + target.getId() + ": " + e.getMessage());
        }
    }

    private Message sendWithTimeout(ServiceRecord target, Message request, int timeout)
            throws MiddlewareException {
        int maxtentativas = 3;
        MiddlewareException lastException = null;

        for (int i = 0; i < maxtentativas; i++) {
            try {
                return internalClient.send(target.getAddress().getHostAddress(), target.getPort(), request);
            } catch (MiddlewareException e) {
                lastException = e;
                System.err.println("[Gateway] Tentativa " + (i + 1) + " falhou para " + target.getId() + ": " + e.getMessage());

                if (i < maxtentativas - 1) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new MiddlewareException("interrupted while retrying to " + target.getId());
                    }
                }
            }
        }
        throw lastException;
    }

    private static Message errorEnvelope(String detail) {
        return new Message(Command.UNKNOWN, List.of("ERRO: " + detail));
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
