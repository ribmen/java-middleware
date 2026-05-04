package com.victor.server;

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

import com.victor.ComponentServer;
import com.victor.RequestHandler;

public class TcpServer implements ComponentServer {
    private ServerSocket serverSocket;
    private boolean running = false;
    private ExecutorService threadPool;

    private static final int THREAD_POOL_SIZE = 500;
    private static final int BUFFER_SIZE = 8192;

    @Override
    public void start(int port, RequestHandler handler) throws Exception {
        
        serverSocket = new ServerSocket(port);
        serverSocket.setReceiveBufferSize(64 * 1024);

        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;
        System.out.println("Servidor TCP escutando na porta " + port);

        new Thread(() -> {
            while(running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(10000);
                    clientSocket.setSendBufferSize(64 * 1024);
                    clientSocket.setReceiveBufferSize(64 * 1024);

                    threadPool.submit(() -> handleClient(clientSocket, handler));

                } catch (IOException e) {
                    if (running) {
                        System.err.println("Erro no servidor TCP: " + e.getMessage());
                    }
                }
            }
        }).start();

        // Thread que monitora thread pool
        new Thread(() -> {
            while(running) {
                try {
                    Thread.sleep(10000);
                    if(threadPool instanceof ThreadPoolExecutor) {
                        ThreadPoolExecutor tpe = (ThreadPoolExecutor) threadPool;
                        System.out.printf("[TCP SERVER] Pool status - ATIVAS: %d, COMPLETADAS: %d, FILA: %d\n", tpe.getActiveCount(), tpe.getCompletedTaskCount(), tpe.getQueue().size());
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private void handleClient(Socket clientSocket, RequestHandler handler) {
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

                StringBuilder requestBuilder = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    requestBuilder.append(line);
                    break;
                }

                String request = requestBuilder.toString();

                if (request.length() == 0) {
                    return;
                }

                if (request.length() > BUFFER_SIZE * 0.9) {
                    System.out.println("[TCP] AVISO: Requisição acima do tamanho: " + request.length() + " bytes");
                }

                String response = handler.handle(request);

                if (response.length() > BUFFER_SIZE) {
                System.err.println("[TCP] AVISO: Resposta grande: " + response.length() + " bytes");
                // TCP pode lidar com mensagens grandes, então não truncamos
                }

                writer.println(response);
                writer.flush();

             } catch (IOException e) {
                System.err.println("Erro ao processar requisição TCP: " + e.getMessage());
             } finally {
                try{
                    clientSocket.close();
                }catch(IOException e){
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
