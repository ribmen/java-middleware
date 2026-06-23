package com.victor.middleware.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.exceptions.ProtocolException;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.RequestHandler;

/**
 * HTTP/1.1 implementation of {@link ComponentServer}. Parses a hand-rolled
 * {@code POST /COMMAND HTTP/1.1} request, reads headers up to a blank line,
 * reads the body up to {@code Content-Length}, synthesizes the canonical
 * {@code "COMMAND|PAYLOAD"} wire form, dispatches it to a
 * {@link RequestHandler}, and writes the response back with
 * {@code Content-Type: text/plain}.
 *
 * <p>Phase 1 keeps the original {@code kvstore} semantics: 50-thread pool, no
 * keep-alive, no chunked encoding, status code resolved by sniffing the
 * handler's payload string for gateway-specific markers (see plan §7.5 — this
 * layering violation is intentionally preserved for Phase 1 and will be
 * replaced by a {@code StatusCodeMapper} SPI in Phase 2).</p>
 */
public class HttpServer implements ComponentServer {

    private static final int THREAD_POOL_SIZE = 50;
    private static final String ORIGIN = "HTTP";

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService threadPool;

    @Override
    public void start(int port, RequestHandler handler) throws MiddlewareException {
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new ConnectionException(ORIGIN, "não foi possível fazer bind na porta " + port, e);
        }

        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        running = true;
        System.out.println("Servidor HTTP (custom) escutando na porta " + port);

        Thread acceptLoop = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.submit(() -> handleClient(clientSocket, handler));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Erro no servidor HTTP: " + e.getMessage());
                    }
                }
            }
        }, "http-server-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
    }

    private void handleClient(Socket clientSocket, RequestHandler handler) {
        OutputStream out = null;
        try {
            out = clientSocket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            // linha de requisição (ex: "POST /ADD_TRANSACTION HTTP/1.1")
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                sendErrorResponse(out, 400, "Bad Request: Empty request line");
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                sendErrorResponse(out, 400, "Bad Request: Invalid request format");
                return;
            }

            String method = requestParts[0];
            String path = requestParts[1];

            // extrair o comando do path (removendo a "/")
            String command = path.startsWith("/") ? path.substring(1) : path;

            // os cabeçalhos (headers) até encontrar uma linha em branco
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                String[] headerParts = headerLine.split(": ", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0], headerParts[1]);
                }
            }

            // corpo (body) da requisição
            String payload = "";
            if ("POST".equalsIgnoreCase(method)) {
                if (headers.containsKey("Content-Length")) {
                    try {
                        int contentLength = Integer.parseInt(headers.get("Content-Length"));
                        char[] bodyChars = new char[contentLength];
                        int read = 0;
                        while (read < contentLength) {
                            int n = reader.read(bodyChars, read, contentLength - read);
                            if (n < 0) {
                                break;
                            }
                            read += n;
                        }
                        payload = new String(bodyChars, 0, read);
                    } catch (NumberFormatException e) {
                        sendErrorResponse(out, 400, "Bad Request: Invalid Content-Length");
                        return;
                    }
                }
            }

            // string no formato que o Gateway espera: "COMMAND|PAYLOAD"
            String gatewayRequest = command + "|" + payload;

            String responsePayload = handler.handle(gatewayRequest);
            byte[] payloadBytes = responsePayload.getBytes("UTF-8");

            int statusCode = resolveStatusCode(command, responsePayload);
            String statusText = statusCode == 200 ? "OK"
                    : (statusCode == 503 ? "Service Unavailable" : "Internal Server Error");

            // montar e enviar a resposta HTTP
            String httpResponseHeaders = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + payloadBytes.length + "\r\n"
                    + "\r\n"; // Linha em branco crucial

            // os headers e DEPOIS o corpo (payload) separadamente.
            out.write(httpResponseHeaders.getBytes("UTF-8"));
            out.write(payloadBytes);
            out.flush();

        } catch (Exception e) {
            System.err.println("Erro ao processar requisição HTTP: " + e.getMessage());
            try {
                if (out != null) {
                    sendErrorResponse(out, 500, "Internal Server Error: " + e.getMessage());
                }
            } catch (IOException ioe) {
                System.err.println("Erro ao enviar resposta de erro: " + ioe.getMessage());
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private int resolveStatusCode(String command, String responsePayload) {
        if (responsePayload == null || responsePayload.isEmpty()) {
            return 500;
        }

        String normalized = responsePayload.toUpperCase();
        boolean isGatewayUnavailable = normalized.contains("ERRO AO ACESSAR O GATEWAY")
                || normalized.contains("NENHUM NODE DISPONÍVEL");

        if (isGatewayUnavailable) {
            return 503;
        }

        if (normalized.startsWith("ERRO") || normalized.contains("|ERRO")) {
            return 500;
        }

        return 200;
    }

    /**
     * Translate a {@link com.victor.middleware.exceptions.MarshalException}
     * to its HTTP status code. Package-private so MarshalledServer's
     * future integration with HttpServer has a single seam to call.
     *
     * <p>Non-marshal {@link MiddlewareException}s return -1 to signal
     * "fall back to the existing resolveStatusCode logic".</p>
     */
    int statusFrom(com.victor.middleware.exceptions.MiddlewareException e) {
        if (e instanceof com.victor.middleware.exceptions.MarshalException me) {
            return me.statusCode();
        }
        return -1;
    }

    private void sendErrorResponse(OutputStream out, int statusCode, String message) throws IOException {
        String statusText = statusCode == 400 ? "Bad Request" : "Internal Server Error";
        String body = statusCode + " " + statusText + ": " + message;
        byte[] bodyBytes = body.getBytes("UTF-8");

        String httpResponse = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "\r\n";

        out.write(httpResponse.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
    }

    @Override
    public void stop() {
        running = false;
        if (threadPool != null) {
            threadPool.shutdown();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("Erro ao fechar ServerSocket HTTP: " + e.getMessage());
            }
        }
        System.out.println("Servidor HTTP parado.");
    }
}