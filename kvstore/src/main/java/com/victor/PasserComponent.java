package com.victor;

public class PasserComponent extends BaseComponent {

    private final ComponentClient componentClient;

    public PasserComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type,
            ComponentType componentType) {
        super(componentPort, gatewayHost, gatewayPort, type, componentType);

        this.componentClient = CommunicationFactory.createClient(type);
    }

    // "ROUTE_TO_WORKER|WRITE|key|value" ou "ROUTE_TO_WORKER|READ|key"
    @Override
    protected RequestHandler getRequestHandler() {
        return (request) -> {
            
            // Remove o prefixo "ROUTE_TO_WORKER|"
            String commandWithPayload = request.substring("ROUTE_TO_WORKER|".length());
            String[] parts = commandWithPayload.split("\\|", 2);
            String command = parts[0];
            String payload = (parts.length > 1) ? parts[1] : "";

            switch (command) {
                case "WRITE":
                    return handleWrite(commandWithPayload);
                case "READ": 
                    return handleRead(commandWithPayload);
                default:
                    return "ERRO: Comando desconhecido: " + command;
            }
        };
    }

    private String handleRead(String commandWithPayload) {
        try {
            // commandWithPayload = "READ|key"
            String requestToGateway = commandWithPayload;
            System.out.println("[PASSER] Repassando operação: " + requestToGateway + " para o GATEWAY");
            return componentClient.send(gatewayHost, gatewayPort, requestToGateway);
        } catch (Exception e) {
            System.err.println("[PASSER] Erro ao tentar READ para o gateway: " + e.getMessage());
            return "[PASSER] Erro ao tentar READ para o gateway: " + e.getMessage();
        }
    }

    private String handleWrite(String commandWithPayload) {
        try {
            // commandWithPayload = "WRITE|key|value"
            String requestToGateway = commandWithPayload;
            System.out.println("[PASSER] Repassando operação: " + requestToGateway + " para o GATEWAY");
            return componentClient.send(gatewayHost, gatewayPort, requestToGateway);
        } catch (Exception e) {
            System.err.println("[PASSER] Erro ao tentar WRITE para o gateway: " + e.getMessage());
            return "[PASSER] Erro ao tentar WRITE para o gateway: " + e.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {

        int myPort = Integer.parseInt(args[0]);
        String gatewayHost = args[1];
        int gatewayPort = Integer.parseInt(args[2]);
        CommunicationType type = CommunicationType.valueOf(args[3].toUpperCase());

        new PasserComponent(myPort, gatewayHost, gatewayPort, type, ComponentType.PASSER_ON).start();
    }
}