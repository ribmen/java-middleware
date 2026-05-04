package com.victor.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import com.victor.ComponentClient;

public class HttpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 5000;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY = 1000;

    @Override
    public String send(String host, int port, String request) throws Exception {
        IOException lastException = null;

        for (int i = 0; i < MAX_RETRIES; i++) {
            try (Socket socket = new Socket(host, port)) {
                socket.setSoTimeout(DEFAULT_TIMEOUT);
                OutputStream out = socket.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // particionar o payload que vem
                String[] parts = request.split("\\|", 2);
                String command = parts[0];
                String payload = (parts.length > 1) ? parts[1] : "";
                byte[] payloadBytes = payload.getBytes("UTF-8");

                // construir a request 
                StringBuilder httpRequest = new StringBuilder();

                httpRequest.append("POST /").append(command).append(" HTTP/1.1\r\n");
                httpRequest.append("Host: ").append(host).append(":").append(port).append("\r\n");
                httpRequest.append("User-Agent: CustomHttpClient/1.0\r\n");
                httpRequest.append("Content-Type: text/plain\r\n");
                httpRequest.append("Content-Length: ").append(payloadBytes.length).append("\r\n");
                httpRequest.append("\r\n"); // Linha em branco

                // enviar a request
                out.write(httpRequest.toString().getBytes("UTF-8"));
                out.write(payloadBytes);
                out.flush();

                String statusLine = in.readLine();

                // Ler headers
                int responseContentLength = 0;
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    if (headerLine.toLowerCase().startsWith("content-length:")) {
                        responseContentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    }
                }

                // Ler o corpo da resposta
                char[] responseBodyChars = new char[responseContentLength];
                in.read(responseBodyChars, 0, responseContentLength);
                
                return new String(responseBodyChars);
            } catch (IOException e) {
                lastException = e;
                System.err.printf("[HTTP CLIENT] Tentativa %d falhou para %s:%d - %s\n", (i + 1), host, port, e.getMessage());
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new IOException("Falha ao enviar requisição HTTP após " + MAX_RETRIES + " tentativas.", lastException);
    }
}
