package com.victor.middleware.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.RequestHandler;

/**
 * TCP implementation of {@link ComponentServer}. Binds a {@link ServerSocket},
 * accepts client connections on a fixed thread pool (500 threads), reads each
 * request as a single line, dispatches it to a {@link RequestHandler}, and
 * writes the handler's response back as one line terminated by {@code println}.
 *
 * <p>A second daemon thread prints thread-pool statistics every 10s for
 * observability. Phase 1 keeps the original {@code kvstore} semantics: 8KB
 * read buffer, 10s per-socket read timeout, log spam on debug, no TLS.</p>
 */
public class TcpServer implements ComponentServer {

    private static final int THREAD_POOL_SIZE = 500;
    private static final int BUFFER_SIZE = 8192;
    private static final int CLIENT_SO_TIMEOUT_MS = 10000;
    private static final int SOCKET_BUFFER_BYTES = 64 * 1024;
    private static final String ORIGIN = "TCP";

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService threadPool;

    @Override
    public void start(int port, RequestHandler handler) throws MiddlewareException {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
        } catch (IOException e) {
            throw new ConnectionException(ORIGIN, "não foi possível fazer bind na porta " + port, e);
        }

        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;
        System.out.println("Servidor TCP escutando na porta " + port);

        Thread acceptLoop = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(CLIENT_SO_TIMEOUT_MS);
                    clientSocket.setSendBufferSize(SOCKET_BUFFER_BYTES);
                    clientSocket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);

                    threadPool.submit(() -> handleClient(clientSocket, handler));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Erro no servidor TCP: " + e.getMessage());
                    }
                }
            }
        }, "tcp-server-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();

        // Thread que monitora thread pool
        Thread monitor = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(10000);
                    if (threadPool instanceof ThreadPoolExecutor) {
                        ThreadPoolExecutor tpe = (ThreadPoolExecutor) threadPool;
                        System.out.printf("[TCP SERVER] Pool status - ATIVAS: %d, COMPLETADAS: %d, FILA: %d%n",
                                tpe.getActiveCount(), tpe.getCompletedTaskCount(), tpe.getQueue().size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "tcp-server-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void handleClient(Socket clientSocket, RequestHandler handler) {
        PrintWriter writer = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter w = new PrintWriter(clientSocket.getOutputStream(), true)) {

            writer = w;
            StringBuilder requestBuilder = new StringBuilder();
            char[] buffer = new char[BUFFER_SIZE];

            int charsRead = reader.read(buffer);

            if (charsRead != -1) {
                requestBuilder.append(buffer, 0, charsRead);
            }

            String request = requestBuilder.toString().trim();

            if (request.length() == 0) {
                return;
            }

            if (request.length() > BUFFER_SIZE * 0.9) {
                System.out.println("[TCP] AVISO: Requisição acima do tamanho: " + request.length() + " bytes");
            }

            String response = handler.handle(request);
            System.out.println("[TCP SERVER] [DEBUG] Handler retornou: '" + response + "'");
            if (response == null) {
                System.err.println("[TCP SERVER] [ERROR] Response é NULL!");
                response = "[TCP SERVER] Erro: resposta nula";
            }

            if (response.length() > BUFFER_SIZE) {
                System.err.println("[TCP] AVISO: Resposta grande: " + response.length() + " bytes");
                // TCP pode lidar com mensagens grandes, então não truncamos
            }

            writer.println(response);
            System.out.println("[TCP SERVER] [DEBUG] Resposta de " + response.length() + " bytes enviada");
            writer.flush();

        } catch (IOException e) {
            System.err.println("Erro ao processar requisição TCP: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[TCP SERVER] Erro ao processar handler: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (writer != null) {
                try {
                    writer.println("[TCP SERVER] Erro interno: " + e.getMessage());
                    writer.flush();
                } catch (Exception ignored) { }
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
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

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("Erro ao fechar ServerSocket: " + e.getMessage());
            }
        }
        System.out.println("Servidor TCP parado.");
    }
}