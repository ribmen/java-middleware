package com.victor.server;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.victor.ComponentServer;
import com.victor.RequestHandler;


public class UdpServer implements ComponentServer {
    private DatagramSocket socket;
    private volatile boolean running = false;
    private ExecutorService threadPool;

    private static final int BUFFER_SIZE = 16384;
    private static final int THREAD_POOL_SIZE = 500;


    @Override
    public void start(int port, RequestHandler handler) throws SocketException {
        socket = new DatagramSocket(port);
        socket.setReceiveBufferSize(1024 * 1024);
        socket.setSendBufferSize(1024 * 1024); 

        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;
        System.out.println("Servidor UDP escutando na porta " + port);

        new Thread( () -> {
            while(running){
                try{
                    byte[] buffer = new byte[BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet); 

                    threadPool.submit( () -> handlePacket(packet, handler) );
                } catch(IOException e){
                    if(running){
                        System.err.println("Erro no servidor UDP: " + e.getMessage());
                    }
                }
            }
        }).start();


        //thread que monitora thread pool 
        // e imprime status a cada 10s
        new Thread(() -> {
            while(running) {
                try{
                    Thread.sleep(10000);
                    if(threadPool instanceof ThreadPoolExecutor){
                        ThreadPoolExecutor tpe = (ThreadPoolExecutor) threadPool;
                        System.out.printf("[UDPServer] Pool status - Ativas: %d, Completadas: %d, Queue: %d\n",
                                         tpe.getActiveCount(), tpe.getCompletedTaskCount(), tpe.getQueue().size());
                    }
                }catch(InterruptedException e){
                    break;
                }
            }
        }).start();
    }

    private void handlePacket(DatagramPacket packet , RequestHandler handler){
        try{
            String request = new String(packet.getData(), 0 , packet.getLength());

            if(packet.getLength() > BUFFER_SIZE * 0.9){
                System.out.println("[UDP] AVISO: Packet grande: " + packet.getLength() + " bytes");
            }

            String response = handler.handle(request);

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
        
        }catch(IOException e) {
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
            }
        }
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        System.out.println("Servidor UDP parado.");
    }

}

