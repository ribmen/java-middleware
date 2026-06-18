package com.victor;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Gateway {

    private final int port;
    private final CommunicationType type;
    private ComponentServer server;

    private final Map<String, ComponentInfo> serviceRegistry = new ConcurrentHashMap<>();
    private final ComponentClient internalClient;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.atomic.AtomicInteger roundRobinCounter = new AtomicInteger(0);
    public Gateway(int port, CommunicationType type) {
        this.port = port;
        this.type = type;
        this.internalClient = CommunicationFactory.createClient(type);
    }

    public void start() throws Exception {
        this.server = CommunicationFactory.createServer(type);

        RequestHandler handler = (requestWithSenderInfo) -> {
            System.out.println("Gateway recebeu: " + requestWithSenderInfo);
            String[] parts = requestWithSenderInfo.split("\\|");
            String command = parts[0];

            if (command.equals("REGISTER")) {
                //  IP_COMPONENTE|PORTA_COMPONENTE|TIPO_COMPONENTE
                return handleRegistration(parts);
            } else if (command.equals("HEARTBEAT")) {
                // IP_COMPONENTE|PORTA_COMPONENTE|TIPO_COMPONENTE
                return handleHeartbeat(parts);
            }

            String payload = requestWithSenderInfo.length() > command.length() + 1
                    ? requestWithSenderInfo.substring(command.length() + 1)
                    : "";

            return routeRequest(command, payload);
        };

        server.start(port, handler);
        startHeartbeatCheck();
        System.out.println("GATEWAY ROUTER iniciado no padrão " + type + "na porta " +port);
    }

    private String handleHeartbeat(String[] parts) {
        try {
            // PADRÃO: HEARTBEAT|IP|PORTA
            String componentId = parts[1] + ":" + parts[2];
            ComponentInfo info = serviceRegistry.get(componentId);
            if (info != null) {
                info.setLastHeartbeat();
                return "OK";
            }

            return "[GATEWAY] Componente não registrado para atualizar HEARTBEAT";
        } catch (Exception e) {
            return "ERRO: Formato de heartbeat inválido.";
        }
    }

    private void startHeartbeatCheck() {
        // A cada 10 segundos, verifica se algum componente não envia heartbeat há mais de 30 segundos.

        heartbeatScheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            serviceRegistry.entrySet().removeIf(entry -> {
                boolean isStale = (now - entry.getValue().getLastHeartbeat()) > 30000;
                if (isStale) {
                    System.out.println("[GATEWAY] Removendo componente inativo: " + entry.getKey());
                }
                return isStale;
            });
        }, port, port, TimeUnit.SECONDS);
    }

    private String handleRegistration(String[] parts) {
        try {
            // PADRÃO: REGISTER|IP|PORTA
            InetAddress address = InetAddress.getByName(parts[1]);
            int componentPort = Integer.parseInt(parts[2]);
            String componentId = address.getHostAddress() + ":" + componentPort;

            serviceRegistry.put(componentId, new ComponentInfo(address, componentPort));
            System.out.println("[GATEWAY] Componente registrado: " + componentId);
            System.out.printf("[GATEWAY] NODES - Workers: %d\n", serviceRegistry.size());

            return "SUCESSO: Registrado.";
        } catch (Exception e) {
            return "ERRO: Falha ao registrar componente. Formato inválido.";
        }
    }

    private String routeRequest(String command, String payload) {
        try {
            if (command.equals("WRITE")) {
                return routeWrite(payload);
            }

            if (command.equals("READ")) {
                return routeRead(payload);
            }

            return "ERRO: Comando desconhecido: " + command;
        } catch (Exception e) {
            System.err.println("[Gateway] Erro de comunicação ao rotear '" + command + "': " + e.getMessage());
            return "ERRO: " + e.toString();
        }
    }

    private String routeWrite(String payload) {
        ComponentInfo target = findAvailableComponent();
        if (target == null) {
            return "[GATEWAY] ERRO: Nenhum WORKER disponivel para WRITE";
        }

        String request = "WRITE" + (payload.isEmpty() ? "" : "|" + payload);
        try {
            String response = sendWithTimeout(target, request, 100000);
            System.out.println("[GATEWAY] WRITE roteado para " + target);
            return response;
        } catch (Exception e) {
            System.err.println("[GATEWAY] WRITE falhou em " + target + ": " + e.getMessage());
            return "ERRO: WRITE_FAILED em " + target + ": " + e.getMessage();
        }
    }

    private String routeRead(String payload) {
        ComponentInfo target = findAvailableComponent();
        if (target == null) {
            return "[GATEWAY] ERRO: Nenhum WORKER disponivel para READ";
        }

        String request = "READ" + (payload.isEmpty() ? "" : "|" + payload);
        try {
            String response = sendWithTimeout(target, request, 100000);
            System.out.println("[GATEWAY] READ roteado para " + target);
            return response;
        } catch (Exception e) {
            System.err.println("[GATEWAY] READ falhou em " + target + ": " + e.getMessage());
            return "ERRO: READ_FAILED em " + target + ": " + e.getMessage();
        }
    }

    private ComponentInfo findAvailableComponent() {
        List<ComponentInfo> candidatos = serviceRegistry.values().stream()
                .filter(c -> (System.currentTimeMillis() - c.getLastHeartbeat()) <= 30000)
                .collect(ArrayList::new, (list, item) -> list.add(item), List::addAll);

        if (candidatos.isEmpty()) {
            return null;
        }
        int index = roundRobinCounter.getAndIncrement() % candidatos.size();
        ComponentInfo escolhido = candidatos.get(index);
        System.out.println("[GATEWAY] Componente escolhido (Round-Robin " + index + "): " + escolhido);
        return escolhido;

    }

    private String sendWithTimeout(ComponentInfo target, String request, int timeout) throws Exception {
        int maxtentativas = 3;

        Exception lastException = null;

        for (int i = 0; i < maxtentativas; i++) {
            try {
                return internalClient.send(target.getAddress().getHostAddress(), target.getPort(), request);
            } catch (Exception e) {
                lastException = e;
                System.err.println("[Gateway] Tentativa " + (i + 1) + " falhou para " + target + ": " + e.getMessage());
                
                if (i < maxtentativas - 1) {
                    Thread.sleep(1000); // Espera aí
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