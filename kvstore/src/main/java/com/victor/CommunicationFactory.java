package com.victor;

import com.victor.client.TcpClient;
import com.victor.client.UdpClient;
import com.victor.client.HttpClient;

import com.victor.server.HttpServer;
import com.victor.server.TcpServer;
import com.victor.server.UdpServer;

public class CommunicationFactory {
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
