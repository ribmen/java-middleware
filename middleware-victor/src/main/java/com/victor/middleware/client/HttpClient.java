package com.victor.middleware.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.exceptions.ProtocolException;
import com.victor.middleware.spi.ComponentClient;

/**
 * HTTP/1.1 implementation of {@link ComponentClient}. The request line is
 * {@code POST /COMMAND HTTP/1.1} where {@code COMMAND} is the path token
 * (the marshalled envelope's {@code verb} field); the body is the
 * marshalled JSON envelope verbatim.
 *
 * <p>The transport is codec-agnostic: this client does not parse or
 * encode the body. {@link com.victor.middleware.marshaller.MarshalledClient}
 * is the layer responsible for producing the envelope; this client only
 * ships bytes over HTTP and reads back the response body up to
 * {@code Content-Length}.</p>
 *
 * <p>Phase 1 keeps the framing simple: status line is read and discarded, only
 * {@code Content-Length} is honored (no chunked transfer encoding, no
 * keep-alive). 5s timeout.</p>
 */
public class HttpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 5000;

    /** Origin tag attached to every {@link ConnectionException} thrown here. */
    private static final String ORIGIN = "HTTP";

    @Override
    public String send(String host, int port, String request) throws MiddlewareException {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(DEFAULT_TIMEOUT);
            socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT);

            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // The body is whatever envelope the caller marshalled — we don't
            // re-encode it. The verb in the envelope doubles as the HTTP
            // path token via the {@code X-Verb} header, but the path itself
            // is left as "/" because the server uses the body, not the path.
            byte[] payloadBytes = request.getBytes("UTF-8");

            StringBuilder httpRequest = new StringBuilder();

            httpRequest.append("POST / HTTP/1.1\r\n");
            httpRequest.append("Host: ").append(host).append(":").append(port).append("\r\n");
            httpRequest.append("User-Agent: CustomHttpClient/1.0\r\n");
            httpRequest.append("Content-Type: application/json\r\n");
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
                    String[] headerParts = headerLine.split(":", 2);
                    if (headerParts.length < 2) {
                        throw new ProtocolException(ORIGIN, "header Content-Length malformado: " + headerLine);
                    }
                    try {
                        responseContentLength = Integer.parseInt(headerParts[1].trim());
                    } catch (NumberFormatException e) {
                        throw new ProtocolException(ORIGIN, "Content-Length não numérico: " + headerLine, e);
                    }
                }
            }

            char[] responseBodyChars = new char[responseContentLength];
            in.read(responseBodyChars, 0, responseContentLength);

            return new String(responseBodyChars);
        } catch (UnknownHostException e) {
            throw new ConnectionException(ORIGIN, "host desconhecido: " + host, e);
        } catch (ConnectException e) {
            throw new ConnectionException(ORIGIN, "conexão recusada para " + host + ":" + port, e);
        } catch (SocketTimeoutException e) {
            throw new ConnectionException(ORIGIN, "timeout na conexão/leitura para " + host + ":" + port, e);
        } catch (IOException e) {
            throw new ConnectionException(ORIGIN, "falha de I/O HTTP para " + host + ":" + port, e);
        }
    }
}