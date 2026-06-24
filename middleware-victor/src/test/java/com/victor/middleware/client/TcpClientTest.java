package com.victor.middleware.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.ConnectionException;

/**
 * Pins the {@link TcpClient} wire contract against a real {@link ServerSocket}
 * echo double — no mocks. Each test:
 *
 * <ol>
 *     <li>Opens a {@code ServerSocket(0)} and reads one line of input from it.</li>
 *     <li>Writes a canned response back to the client.</li>
 *     <li>Asserts what the client framed (the request line) and what it
 *         returned (the response line).</li>
 * </ol>
 *
 * <p>The double server runs on the same JVM as the client, so the test is
 * in-process — no real network hops. This exercises the full blocking-IO
 * path: {@code Socket.connect}, {@code PrintWriter.println},
 * {@code BufferedReader.readLine}, timeout handling.</p>
 *
 * <p>After the pipe codec removal the wire format is the JSON envelope
 * itself — there is no separate framing layer. TCP/UDP are "raw JSON
 * over sockets".</p>
 */
class TcpClientTest {

    private ServerSocket server;
    private int port;
    private Thread serverThread;

    @BeforeEach
    void startEchoServer() throws IOException {
        server = new ServerSocket(0);
        port = server.getLocalPort();
    }

    @AfterEach
    void stopEchoServer() throws Exception {
        if (server != null && !server.isClosed()) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    /** Standard round-trip: client sends a JSON envelope, server echoes, client returns it. */
    @Test
    void roundTripsRequestLineAndResponse() throws Exception {
        serverThread = forkServer(req -> "ECHO:" + req);

        TcpClient client = new TcpClient();
        String envelope = "{\"verb\":\"WRITE\",\"args\":[\"k\",\"v\"],\"body\":{}}";
        String response = client.send("127.0.0.1", port, envelope);

        assertEquals("ECHO:" + envelope, response);
    }

    /**
     * Captures the exact bytes the client wrote so we can verify the
     * request framing — TCP just ships the JSON envelope bytes
     * terminated by a newline so the echo server can read a line.
     */
    @Test
    void writesRequestTerminatedByNewline() throws Exception {
        StringBuilder captured = new StringBuilder();
        serverThread = forkServer(req -> {
            captured.append(req);
            return "ok";
        });

        String envelope = "{\"verb\":\"READ\",\"args\":[\"smoke-key\"],\"body\":{}}";
        new TcpClient().send("127.0.0.1", port, envelope);

        assertEquals(envelope, captured.toString(),
                "TcpClient must write the raw JSON envelope with no quoting or padding");
    }

    /** Empty response from the server is returned as empty string, not null. */
    @Test
    void emptyServerResponseReturnsEmptyString() throws Exception {
        serverThread = forkServer(req -> "");

        String response = new TcpClient().send("127.0.0.1", port,
                "{\"verb\":\"PING\",\"args\":[],\"body\":{}}");

        assertEquals("", response);
    }

    /**
     * Connecting to a port nothing is listening on surfaces as a
     * {@link ConnectionException} with the {@code "TCP"} origin tag —
     * never as a raw {@link IOException} or NPE.
     */
    @Test
    void connectionRefusedSurfacesAsConnectionException() throws Exception {
        // Stop the server so the port refuses connections.
        server.close();

        ConnectionException ex = assertThrows(ConnectionException.class,
                () -> new TcpClient().send("127.0.0.1", port,
                        "{\"verb\":\"WRITE\",\"args\":[\"k\"],\"body\":{}}"));

        assertEquals("TCP", ex.getOrigin(),
                "origin tag should identify TCP transport for caller diagnostics");
    }

    /** Fork a one-shot echo server: accept one connection, read a line, write a reply. */
    private Thread forkServer(java.util.function.Function<String, String> handler) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                String request = in.readLine();
                if (request != null) {
                    out.println(handler.apply(request));
                }
            } catch (IOException e) {
                // server.close() during tearDown — fine, ignore
            }
        }, "tcp-client-test-server");
        t.setDaemon(true);
        t.start();
        return t;
    }
}