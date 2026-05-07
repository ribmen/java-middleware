package com.victor.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import com.victor.ComponentClient;

public class HttpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 5000;

    @Override
    public String send(String host, int port, String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(DEFAULT_TIMEOUT);
            socket.connect(new java.net.InetSocketAddress(host, port), DEFAULT_TIMEOUT);

            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String[] parts = request.split("\\|", 2);
            String command = parts[0];
            String payload = (parts.length > 1) ? parts[1] : "";
            byte[] payloadBytes = payload.getBytes("UTF-8");

            StringBuilder httpRequest = new StringBuilder();

            httpRequest.append("POST /").append(command).append(" HTTP/1.1\r\n");
            httpRequest.append("Host: ").append(host).append(":").append(port).append("\r\n");
            httpRequest.append("User-Agent: CustomHttpClient/1.0\r\n");
            httpRequest.append("Content-Type: text/plain\r\n");
            httpRequest.append("Content-Length: ").append(payloadBytes.length).append("\r\n");
            httpRequest.append("\r\n");

            out.write(httpRequest.toString().getBytes("UTF-8"));
            out.write(payloadBytes);
            out.flush();

            in.readLine();

            int responseContentLength = 0;
            String headerLine;
            while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    responseContentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                }
            }

            char[] responseBodyChars = new char[responseContentLength];
            in.read(responseBodyChars, 0, responseContentLength);

            return new String(responseBodyChars);
        }
    }
}
