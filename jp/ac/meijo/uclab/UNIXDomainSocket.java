/*******************************************************************************
 * Copyright (c) 2024 Kengo Shimizu and UCLab.
 *
 * Contributors:
 *     Kengo Shimizu - implementation
 *******************************************************************************/

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.SocketException;

import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

public class UNIXDomainSocket implements Runnable{
    private final String SOCKET_FILE_PATH = "";

    @Override
    public void run() {
        File socketFile = new File(SOCKET_FILE_PATH);
        if (socketFile.exists()) {
            // 古いソケットファイルを削除
            if (!socketFile.delete()) {
                LOG.error("Failed to Delete the File: " + SOCKET_FILE_PATH);
            }
        }
        AFUNIXSocketAddress socketAddress;
        try {
            socketAddress = AFUNIXSocketAddress.of(socketFile);
        } catch (SocketException e) {
            e.printStackTrace();
            return;
        }
        try (AFUNIXServerSocket serverSocket = AFUNIXServerSocket.bindOn(socketAddress)) {
            LOG.info("Waiting for connection...");
            while (!serverSocket.isClosed()) {
                AFUNIXSocket sock = serverSocket.accept();
                LOG.info("Connected: " + sock);
                new Thread(() -> handleConnection(sock)).start();
            }
        } catch (IOException e) {
            LOG.error("UnixDomainSocketServer error: ", e);
        }
    }

    private void handleConnection(AFUNIXSocket sock) {
        try (InputStream is = sock.getInputStream()) {
            String receivedData = receive(is);
            
        } catch (IOException e) {
            LOG.error("Connection handling error: ", e);
        }
    }
    
    private String receive(InputStream is) throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead = is.read(buffer);
        if (bytesRead == -1) {
            return null;
        }
        return new String(buffer, 0, bytesRead);
    }
}
