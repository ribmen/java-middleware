package com.victor.middleware.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.spi.ComponentClient;

/**
 * TCP implementation of {@link ComponentClient}. Opens a {@link Socket}, writes
 * the request as a single line (terminated by {@code println}), and reads one
 * line back as the response.
 *
 * <p>Phase 1 keeps the original {@code kvstore} semantics: 5s connect/read
 * timeout, no TLS, no pipelining. Raw {@link IOException}s are translated into
 * {@link ConnectionException} carrying the {@code "TCP"} origin tag so callers
 * can distinguish transport failures from protocol/handler errors.</p>
 */
public class TcpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 5000;

    /** Origin tag attached to every {@link ConnectionException} thrown here. */
    private static final String ORIGIN = "TCP";

    @Override
    public String send(String host, int port, String request) throws MiddlewareException {
        Socket socket = null;
        BufferedReader reader = null;
        PrintWriter writer = null;

        try {
            socket = new Socket();
            socket.setSoTimeout(DEFAULT_TIMEOUT);
            socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT);

            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.println(request);
            writer.flush();

            String line = reader.readLine();
            return line != null ? line : "";
        } catch (UnknownHostException e) {
            throw new ConnectionException(ORIGIN, "host desconhecido: " + host, e);
        } catch (ConnectException e) {
            throw new ConnectionException(ORIGIN, "conexão recusada para " + host + ":" + port, e);
        } catch (SocketTimeoutException e) {
            throw new ConnectionException(ORIGIN, "timeout na conexão/leitura para " + host + ":" + port, e);
        } catch (IOException e) {
            throw new ConnectionException(ORIGIN, "falha de I/O TCP para " + host + ":" + port, e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException e) { /* swallow on close */ }
            }
            if (writer != null) {
                writer.close();
            }
            if (socket != null) {
                try { socket.close(); } catch (IOException e) { /* swallow on close */ }
            }
        }
    }
}