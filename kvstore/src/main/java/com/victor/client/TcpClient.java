package com.victor.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import com.victor.ComponentClient;

public class TcpClient implements ComponentClient {

    private static final int DEFAULT_TIMEOUT = 5000;

    @Override
    public String send(String host, int port, String request) throws Exception {
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
            throw new IOException("[TCP CLIENT] Host desconhecido: " + host, e);
        } catch (ConnectException e) {
            throw new IOException("conexão recusada para " + host + ":" + port, e);
        } catch (SocketTimeoutException e) {
            throw new IOException("timeout na conexão/leitura para " + host + ":" + port, e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException e) { }
            }
            if (writer != null) {
                writer.close();
            }
            if (socket != null) {
                try { socket.close(); } catch (IOException e) { }
            }
        }
    }
}
