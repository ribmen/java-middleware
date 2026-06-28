package com.victor;

import java.util.List;

import com.victor.business.KVStore;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.TypedRequestHandler;

/**
 * Worker component: holds a {@link KVStore} and services typed
 * {@code WRITE}/{@code READ} requests. Registers with the gateway on
 * startup (handled by {@link BaseComponent}) and processes incoming
 * messages through a {@link TypedRequestHandler}.
 *
 * <p>Phase 5D pipe-removal: the handler receives a fully-decoded
 * {@link Message} (the JSON envelope is parsed by {@code MarshalledServer}
 * before dispatch). The previous {@code request.split("\\|", 3)} is
 * gone — there is no pipe to split. Arguments come pre-parsed in
 * {@link Message#args()}; the verb is {@link Message#command()}.</p>
 */
public class WorkerComponent extends BaseComponent {

    private final KVStore kvStore;

    public WorkerComponent(int componentPort, String gatewayHost, int gatewayPort, CommunicationType type) {

        super(componentPort, gatewayHost, gatewayPort, type);

        this.kvStore = new KVStore(new java.util.HashMap<>());
    }

    @Override
    public TypedRequestHandler getRequestHandler() {
        return (request) -> {
            Command command = request.command();
            String key = request.firstArg();

            System.out.println("[WORKER] Recebeu comando: " + command.wireForm() + " | key=" + key);

            return switch (command) {
                case WRITE -> handleWrite(request);
                case READ -> handleRead(request);
                default -> new Message(Command.UNKNOWN,
                        List.of("ERRO: FUNCTION NOT FOUND: " + command.wireForm()));
            };
        };
    }

    private Message handleWrite(Message msg) {
        try {
            String key = msg.firstArg();
            String value = msg.secondArg();
            String result = kvStore.write(key, value);
            return new Message(Command.OK, List.of(result));
        } catch (RuntimeException e) {
            return new Message(Command.UNKNOWN,
                    List.of("ERRO: WRITE falhou: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private Message handleRead(Message msg) {
        try {
            String key = msg.firstArg();
            String result = kvStore.read(key).toString();
            return new Message(Command.OK, List.of(result));
        } catch (RuntimeException e) {
            // KVStore.read throws IllegalArgumentException for missing keys;
            // wrap uniformly so the gateway sees a typed UNKNOWN response.
            return new Message(Command.UNKNOWN,
                    List.of("ERRO: " + e.getMessage()));
        }
    }

    public static void main(String[] args) throws Exception {

        int myPort = Integer.parseInt(args[0]);
        String gatewayHost = args[1];
        int gatewayPort = Integer.parseInt(args[2]);
        CommunicationType type = CommunicationType.valueOf(args[3].toUpperCase());

        new WorkerComponent(myPort, gatewayHost, gatewayPort, type).start();
    }

}
