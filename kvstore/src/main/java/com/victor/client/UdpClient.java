package com.victor.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import com.victor.ComponentClient;

public class UdpClient implements ComponentClient {
    private static final int DEFAULT_TIMEOUT = 1000;

    @Override 
    public String send(String host, int port, String request) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()){
            InetAddress addres = InetAddress.getByName(host);
            byte[] requestBytes = request.getBytes();

            DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length, addres, port);
            socket.send(requestPacket);

            byte[] buffer = new byte[16384];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);

            socket.setSoTimeout(DEFAULT_TIMEOUT);
            socket.receive(responsePacket);

            return new String(responsePacket.getData(), 0, responsePacket.getLength());
        }
    }

}
