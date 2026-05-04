package com.victor.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import com.victor.ComponentClient;

public class TcpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 5000;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY = 1000;

    @Override
    public String send(String host, int port, String request) throws Exception {
        return sendWithRetry(host, port, request, DEFAULT_TIMEOUT, MAX_RETRIES);
    }

    private String sendWithRetry(String host, int port, String request, int defaultTimeoutMs, int maxRetries) throws IOException {
        IOException lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            Socket socket = null;
            BufferedReader reader = null;
            PrintWriter writer = null;

            try {
                socket = new Socket();
                socket.setSoTimeout(defaultTimeoutMs);
                socket.connect(new InetSocketAddress(host, port), defaultTimeoutMs);

                //configura streams
                writer = new PrintWriter(socket.getOutputStream(), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // enviar requisição
                writer.println(request);
                writer.flush();

                StringBuilder responseBuilder = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                    break;
                }

                String response = responseBuilder.toString();

                if (i > 1) {
                    System.out.println("[TCP CLIENT] Requisição atendida após " + i + " tentativas");
                }

                return response;
            } catch (UnknownHostException e) {
                lastException = new IOException("[TCP CLIENT] Host desconhecido: " + host, e);
                System.err.printf("[TCPClient] host desconhecido %s:%d (tentativa %d)\n", host, port, i);
                break; 
            } catch (ConnectException e) {
                lastException = new IOException("conexão recusada para " + host + ":" + port, e);
                System.err.printf("[TCPClient] conexão recusada %s:%d (tentativa %d)\n", host, port, i);
                
            } catch (SocketTimeoutException e) {
                lastException = new IOException("timeout na conexão/leitura para " + host + ":" + port, e);
                System.err.printf("[TCPClient] timeout %s:%d (tentativa %d)\n", host, port, i);
                
            } catch (IOException e) {
                lastException = new IOException("erro de E/S na tentativa " + i + ": " + e.getMessage(), e);
                System.err.printf("[TCPClient] erro E/S %s:%d (tentativa %d): %s\n", host, port, i, e.getMessage());

            } finally {
                // 
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) {  }
                }
                if (writer != null) {
                    writer.close();
                }
                if (socket != null) {
                    try { socket.close(); } catch (IOException e) { }
                }
            }

            if(i < maxRetries){
                try{
                    Thread.sleep(RETRY_DELAY);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    throw new IOException("Tentativa interrompida", e);
                }
            }
        }

        throw new IOException("Falha após " + maxRetries + "tentativas: " + (lastException != null ? lastException.getMessage() : "Erro desconhecido"), lastException);
    }
}
