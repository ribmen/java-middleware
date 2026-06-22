package com.victor.middleware.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.RequestHandler;

/**
 * UDP implementation of {@link ComponentServer}. Binds a {@link DatagramSocket}
 * and on each incoming packet dispatches the payload string to a
 * {@link RequestHandler}; the handler's response is sent back to the original
 * sender's address/port as a single datagram.
 *
 * <p>Phase 1 keeps the original {@code kvstore} semantics: 16KB packet buffer,
 * 500-thread pool, responses larger than the buffer are replaced with a
 * short error string rather than being truncated. A monitor thread prints pool
 * statistics every 10s.</p>
 */
public class UdpServer implements ComponentServer {

    private static final int BUFFER_SIZE = 16384;
    private static final int THREAD_POOL_SIZE = 500;
    private static final int SOCKET_BUFFER_BYTES = 1024 * 1024;
    private static final String ORIGIN = "UDP";

    private DatagramSocket socket;
    private volatile boolean running = false;
    private ExecutorService threadPool;

    @Override
    public void start(int port, RequestHandler handler) throws MiddlewareException {
        try {
            socket = new DatagramSocket(port);
            socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
            socket.setSendBufferSize(SOCKET_BUFFER_BYTES);
        } catch (SocketException e) {
            throw new ConnectionException(ORIGIN, "não foi possível fazer bind na porta " + port, e);
        }

        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;
        System.out.println("Servidor UDP escutando na porta " + port);

        Thread receiveLoop = new Thread(() -> {
            while (running) {
                try {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    threadPool.submit(() -> handlePacket(packet, handler));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Erro no servidor UDP: " + e.getMessage());
                    }
                }
            }
        }, "udp-server-receive");
        receiveLoop.setDaemon(true);
        receiveLoop.start();

        // thread que monitora thread pool e imprime status a cada 10s
        Thread monitor = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(10000);
                    if (threadPool instanceof ThreadPoolExecutor) {
                        ThreadPoolExecutor tpe = (ThreadPoolExecutor) threadPool;
                        System.out.printf("[UDPServer] Pool status - Ativas: %d, Completadas: %d, Queue: %d%n",
                                tpe.getActiveCount(), tpe.getCompletedTaskCount(), tpe.getQueue().size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "udp-server-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void handlePacket(DatagramPacket packet, RequestHandler handler) {
        try {
            String request = new String(packet.getData(), 0, packet.getLength());

            if (packet.getLength() > BUFFER_SIZE * 0.9) {
                System.out.println("[UDP] AVISO: Packet grande: " + packet.getLength() + " bytes");
            }

            String response;
            try {
                response = handler.handle(request);
            } catch (MiddlewareException e) {
                System.err.println("[UDP] Handler falhou: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                response = "ERRO: " + e.getMessage();
            }

            byte[] responseBytes = response.getBytes();
            if (responseBytes.length > BUFFER_SIZE) {
                System.err.println("[UDP] ERRO: Resposta muito grande: " + responseBytes.length + " bytes");
                response = "ERRO: Resposta muito grande para UDP";
                responseBytes = response.getBytes();
            }

            DatagramPacket responsePacket = new DatagramPacket(
                    responseBytes, responseBytes.length, packet.getAddress(), packet.getPort());

            synchronized (socket) { // Sincroniza envio
                socket.send(responsePacket);
            }

        } catch (IOException e) {
            System.err.println("[UDP] Erro ao processar pacote: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;

        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        System.out.println("Servidor UDP parado.");
    }
}