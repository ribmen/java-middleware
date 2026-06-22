package com.victor.middleware.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import com.victor.middleware.exceptions.ConnectionException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.spi.ComponentClient;

/**
 * UDP implementation of {@link ComponentClient}. Sends the entire {@code request}
 * string as a single {@link DatagramPacket} and blocks for one reply, up to a
 * 1s read timeout. Response buffer is fixed at 16KB — anything larger will be
 * truncated and the caller will see a short string (no protocol-level
 * fragmentation in Phase 1).
 *
 * <p>Phase 1 keeps the original {@code kvstore} semantics: one-shot datagram
 * socket (no connection state), 1s read timeout, no retransmission.</p>
 */
public class UdpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 1000;
    private static final int BUFFER_SIZE = 16384;

    /** Origin tag attached to every {@link ConnectionException} thrown here. */
    private static final String ORIGIN = "UDP";

    @Override
    public String send(String host, int port, String request) throws MiddlewareException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(host);
            byte[] requestBytes = request.getBytes();

            DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length, address, port);
            socket.send(requestPacket);

            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);

            socket.setSoTimeout(DEFAULT_TIMEOUT);
            socket.receive(responsePacket);

            return new String(responsePacket.getData(), 0, responsePacket.getLength());
        } catch (UnknownHostException e) {
            throw new ConnectionException(ORIGIN, "host desconhecido: " + host, e);
        } catch (SocketTimeoutException e) {
            throw new ConnectionException(ORIGIN, "timeout na leitura para " + host + ":" + port, e);
        } catch (IOException e) {
            throw new ConnectionException(ORIGIN, "falha de I/O UDP para " + host + ":" + port, e);
        }
    }
}