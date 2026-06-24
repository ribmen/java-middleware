package com.victor.middleware.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.ProtocolException;

/**
 * Pins the {@link HttpClient} wire contract against a real
 * {@link ServerSocket} that speaks minimal HTTP/1.1. No mocks.
 *
 * <p>The contract has two halves:</p>
 * <ol>
 *     <li>The request framing: a {@code POST / HTTP/1.1} with the
 *         JSON envelope sent verbatim as the body.</li>
 *     <li>The response parsing: status line discarded, Content-Length
 *         honored for the body.</li>
 * </ol>
 *
 * <p>After the pipe codec removal the client is codec-agnostic — it
 * ships whatever bytes the caller hands it. The marshaller is the only
 * layer that knows the envelope shape; this client is just transport.</p>
 */
class HttpClientTest {

    private ServerSocket server;
    private int port;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        server = new ServerSocket(0);
        port = server.getLocalPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null && !server.isClosed()) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    /**
     * Request framing: a JSON envelope passed verbatim must reach the
     * server with {@code POST / HTTP/1.1} framing and the body bytes
     * unchanged. The verb travels in the JSON envelope, not the path.
     */
    @Test
    void framesRequestAsPostWithJsonBody() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> wire = new java.util.concurrent.atomic.AtomicReference<>();
        serverThread = forkServer(req -> {
            wire.set(req.wireAsString());
            return cannedResponse("Written");
        });

        String envelope = "{\"verb\":\"WRITE\",\"args\":[\"k\",\"v\"],\"body\":{}}";
        new HttpClient().send("127.0.0.1", port, envelope);

        String captured = wire.get();
        assertTrue(captured.startsWith("POST / HTTP/1.1\r\n"),
                "expected POST / request line, got: " + captured);
        assertTrue(captured.contains("Content-Type: application/json\r\n"),
                "expected Content-Type application/json, got: " + captured);
        assertTrue(captured.contains("Content-Length: "
                        + envelope.getBytes(StandardCharsets.UTF_8).length + "\r\n"),
                "expected Content-Length matching body bytes, got: " + captured);
        assertTrue(captured.endsWith("\r\n\r\n" + envelope),
                "expected JSON envelope as body, got: " + captured);
    }

    /** Standard happy path: server returns a body, client returns it. */
    @Test
    void returnsBodyFromContentLengthResponse() throws Exception {
        serverThread = forkServer(req -> cannedResponse("Hello world"));

        String response = new HttpClient().send("127.0.0.1", port,
                "{\"verb\":\"READ\",\"args\":[\"k\"],\"body\":{}}");

        assertEquals("Hello world", response);
    }

    /**
     * Response with no body returns empty string. {@code Content-Length: 0}
     * should produce a zero-length body, never an NPE or hang on
     * {@code read()}.
     */
    @Test
    void zeroContentLengthResponseReturnsEmptyString() throws Exception {
        serverThread = forkServer(req -> cannedResponse(""));

        String response = new HttpClient().send("127.0.0.1", port,
                "{\"verb\":\"WRITE\",\"args\":[\"k\",\"v\"],\"body\":{}}");

        assertEquals("", response);
    }

    /**
     * Malformed {@code Content-Length} (non-numeric) is a protocol
     * violation: client throws {@link ProtocolException} with the
     * {@code "HTTP"} origin tag rather than crashing or returning
     * garbage.
     */
    @Test
    void nonNumericContentLengthThrowsProtocolException() throws Exception {
        // Hand-crafted response with a bad header.
        serverThread = forkServer(req -> "HTTP/1.1 200 OK\r\nContent-Length: not-a-number\r\n\r\n");

        assertThrows(ProtocolException.class,
                () -> new HttpClient().send("127.0.0.1", port,
                        "{\"verb\":\"WRITE\",\"args\":[\"k\"],\"body\":{}}"));
    }

    /** Connection refused → {@link ConnectionException} with origin tag. */
    @Test
    void connectionRefusedSurfacesAsConnectionException() throws Exception {
        server.close();

        ConnectionException ex = assertThrows(ConnectionException.class,
                () -> new HttpClient().send("127.0.0.1", port,
                        "{\"verb\":\"WRITE\",\"args\":[\"k\"],\"body\":{}}"));

        assertEquals("HTTP", ex.getOrigin());
    }

    // -- server helpers --------------------------------------------------------

    /**
     * Captured request: the raw wire bytes (request line + headers + body)
     * plus the parsed body, so tests can assert on framing and on payload.
     */
    private record CapturedRequest(byte[] wire, int wireLength, byte[] body, int bodyLength) {

        /** Raw wire as a UTF-8 string — for framing assertions. */
        String wireAsString() {
            return new String(java.util.Arrays.copyOf(wire, wireLength), StandardCharsets.UTF_8);
        }

        /** Body as a UTF-8 string — for payload assertions. */
        String bodyAsString() {
            return new String(java.util.Arrays.copyOf(body, bodyLength), StandardCharsets.UTF_8);
        }
    }

    private Thread forkServer(java.util.function.Function<CapturedRequest, String> handler) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                // Read raw bytes one at a time. Detect lines by \r\n.
                // Crucially we do NOT use BufferedReader here — its read-ahead
                // would silently consume body bytes that we still need to read.
                java.io.ByteArrayOutputStream wireBuf = new java.io.ByteArrayOutputStream();
                java.io.InputStream raw = s.getInputStream();

                int contentLength = 0;
                java.io.ByteArrayOutputStream lineBuf = new java.io.ByteArrayOutputStream();
                int prev = -1;
                int b;
                while ((b = raw.read()) != -1) {
                    wireBuf.write(b);
                    if (b == '\n' && prev == '\r') {
                        // End of line. Strip the trailing CRLF.
                        byte[] lineBytes = lineBuf.toByteArray();
                        if (lineBytes.length == 0) {
                            // Blank line — end of headers.
                            break;
                        }
                        String line = new String(lineBytes, 0, lineBytes.length, StandardCharsets.UTF_8);
                        if (line.toLowerCase().startsWith("content-length:")) {
                            try {
                                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                            } catch (NumberFormatException ignored) { /* non-numeric test */ }
                        }
                        lineBuf.reset();
                    } else if (b != '\r') {
                        lineBuf.write(b);
                    }
                    prev = b;
                }

                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = raw.read(body, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                    wireBuf.write(body, read - n, n);
                }

                CapturedRequest req = new CapturedRequest(
                        wireBuf.toByteArray(),
                        wireBuf.size(),
                        body,
                        read);

                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                out.print(handler.apply(req));
                out.flush();
            } catch (Exception e) {
                // server.close() during tearDown — fine, ignore
            }
        }, "http-client-test-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Build a minimal valid HTTP/1.1 response with the given body and a
     * correct Content-Length header.
     */
    private static String cannedResponse(String body) {
        return "HTTP/1.1 200 OK\r\n" +
               "Content-Type: text/plain\r\n" +
               "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
               "\r\n" +
               body;
    }
}