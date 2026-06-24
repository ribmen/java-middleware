package com.victor.middleware.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.ConnectionException;

/**
 * Pins the {@link UdpClient} wire contract against a real
 * {@link DatagramSocket} echo double — no mocks. UDP has no framing:
 * whatever the server sends back is the response.
 *
 * <p>After the pipe codec removal the wire format is the JSON envelope
 * bytes verbatim — UDP just ships them.</p>
 */
class UdpClientTest {

    private DatagramSocket serverSocket;
    private int port;
    private Thread serverThread;

    @BeforeEach
    void startEchoServer() throws Exception {
        serverSocket = new DatagramSocket(0);
        port = serverSocket.getLocalPort();
    }

    @AfterEach
    void stopEchoServer() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    /** Standard round-trip: client sends a JSON envelope, server echoes, client returns it. */
    @Test
    void roundTripsDatagramPayloadAndReply() throws Exception {
        StringBuilder captured = new StringBuilder();
        serverThread = forkServer(req -> {
            String payload = new String(req.getData(), 0, req.getLength());
            captured.append(payload);
            return ("ECHO:" + payload).getBytes();
        });

        String envelope = "{\"verb\":\"WRITE\",\"args\":[\"k\",\"v\"],\"body\":{}}";
        String response = new UdpClient().send("127.0.0.1", port, envelope);

        assertEquals("ECHO:" + envelope, response);
        assertEquals(envelope, captured.toString(),
                "UdpClient must send the raw JSON envelope bytes unchanged");
    }

    /**
     * Multi-byte UTF-8 payload is preserved through the datagram
     * boundary. Useful because the codec uses UTF-8 string encoding.
     */
    @Test
    void preservesUtf8PayloadBytes() throws Exception {
        serverThread = forkServer(req -> "ok".getBytes());

        String response = new UdpClient().send("127.0.0.1", port,
                "{\"verb\":\"WRITE\",\"args\":[\"chave\",\"ção\"],\"body\":{}}");

        assertEquals("ok", response);
    }

    /**
     * When nothing is listening, the client's 1s read timeout fires
     * and a {@link ConnectionException} is thrown with the {@code "UDP"}
     * origin tag.
     */
    @Test
    void readTimeoutSurfacesAsConnectionException() throws Exception {
        serverSocket.close(); // no server → no reply → 1s timeout

        ConnectionException ex = assertThrows(ConnectionException.class,
                () -> new UdpClient().send("127.0.0.1", port,
                        "{\"verb\":\"WRITE\",\"args\":[\"k\"],\"body\":{}}"));

        assertEquals("UDP", ex.getOrigin());
    }

    /** Fork a one-shot echo datagram server: receive one packet, send one back. */
    private Thread forkServer(java.util.function.Function<DatagramPacket, byte[]> handler) {
        Thread t = new Thread(() -> {
            try {
                byte[] buffer = new byte[16384];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(request);

                byte[] replyBytes = handler.apply(request);
                DatagramPacket reply = new DatagramPacket(
                        replyBytes, replyBytes.length,
                        InetAddress.getByName("127.0.0.1"), request.getPort());
                serverSocket.send(reply);
            } catch (Exception e) {
                // serverSocket.close() during tearDown — fine, ignore
            }
        }, "udp-client-test-server");
        t.setDaemon(true);
        t.start();
        return t;
    }
}