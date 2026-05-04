package com.victor;

import com.victor.business.KVStore;

public class WorkerComponent extends BaseComponent {

    private final ComponentClient clientToGateway;
    private KVStore kvStore;
    
    public WorkerComponent(int componentPort, String gatewayHost, int gatewayPort, ComponentType componentType, CommunicationType type) {
        
        super(componentPort, gatewayHost, gatewayPort, type, componentType);

        this.kvStore = new KVStore(new java.util.HashMap<>());
        this.clientToGateway = CommunicationFactory.createClient(type);
    }

    @Override
    public RequestHandler getRequestHandler() {
        return (request) -> {
            String[] parts = request.split("\\|", 3);
            String command = parts[0];
            String key = parts.length > 1 ? parts[1] : "";
            String value = parts.length > 2 ? parts[2] : "";

            System.out.println("[WORKER] Recebeu comando: " + command + " | key=" + key);

            switch (command) {
                case "WRITE":
                    return handleWrite(key, value);
                case "READ": 
                    return handleRead(key);
                default:
                    return "ERRO: FUNCTION NOT FOUND";
            }
        };
    }

    private String handleWrite(String key, String value) {
        return kvStore.write(key, value);
    }

    private String handleRead(String key) {
        return kvStore.read(key).toString();
    }
    
    public static void main(String[] args) throws Exception {

        int myPort = Integer.parseInt(args[0]);
        String gatewayHost = args[1];
        int gatewayPort = Integer.parseInt(args[2]);
        CommunicationType type = CommunicationType.valueOf(args[3].toUpperCase());

        new WorkerComponent(myPort, gatewayHost, gatewayPort, ComponentType.WORKER, type).start();
    }

}
