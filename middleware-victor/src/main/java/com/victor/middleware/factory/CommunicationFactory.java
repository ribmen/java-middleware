package com.victor.middleware.factory;

import com.victor.middleware.client.HttpClient;
import com.victor.middleware.client.TcpClient;
import com.victor.middleware.client.UdpClient;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.server.HttpServer;
import com.victor.middleware.server.TcpServer;
import com.victor.middleware.server.UdpServer;
import com.victor.middleware.spi.ComponentClient;
import com.victor.middleware.spi.ComponentServer;

/**
 * Single switchboard that maps a {@link CommunicationType} to a concrete
 * {@link ComponentServer} or {@link ComponentClient} implementation.
 *
 * <p>Phase 1 keeps the original {@code kvstore} semantics: the factory is the
 * only place where protocol choice is decided, callers depend only on the
 * {@code spi} interfaces, and the {@code switch} is exhaustive over the
 * {@link CommunicationType} enum.</p>
 *
 * <p>Marked {@code final} with a private constructor: this is a namespace, not
 * a type to instantiate or extend. Failures from this class are programmer
 * errors (an unrecognized enum value), so they surface as unchecked
 * {@link IllegalArgumentException} — distinct from {@code MiddlewareException}
 * which signals runtime transport failures.</p>
 */
public final class CommunicationFactory {

    private CommunicationFactory() {
        // utility class — not meant to be instantiated
    }

    public static ComponentServer createServer(CommunicationType type) {
        switch (type) {
            case TCP:
                return new TcpServer();
            case HTTP:
                return new HttpServer();
            case UDP:
                return new UdpServer();
            default:
                throw new IllegalArgumentException("Tipo de servidor não suportado ou desconhecido: " + type);
        }
    }

    public static ComponentClient createClient(CommunicationType type) {
        switch (type) {
            case TCP:
                return new TcpClient();
            case HTTP:
                return new HttpClient();
            case UDP:
                return new UdpClient();
            default:
                throw new IllegalArgumentException("Tipo de client não suportado ou desconhecido: " + type);
        }
    }
}