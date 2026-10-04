package com.benchmark.p2p;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;

public class NoP2P {
    public static void main(String[] args) throws Exception {
        int minhaPorta = Integer.parseInt(args[0]);
        String peerHost = args.length > 1 ? args[1] : null;
        int peerPorta = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        long tamanhoArquivo = args.length > 3 ? Long.parseLong(args[3]) : 50 * 1024 * 1024L;

        // Thread para atuar como servidor (seeder/peer)
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(minhaPorta)) {
                System.out.println("Nó P2P escutando na porta " + minhaPorta);
                while (true) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try (Socket s = socket; OutputStream out = s.getOutputStream()) {
                            GeradorDados.enviarDados(out, tamanhoArquivo);
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception e) {
                System.err.println("Erro no servidor P2P: " + e.getMessage());
            }
        }).start();

        // Se houver um peer definido, realiza o download dele descartando o arquivo
        if (peerHost != null && peerPorta > 0) {
            Thread.sleep(1000); // Aguarda o peer inicializar
            long inicio = System.currentTimeMillis();
            try (Socket socket = new Socket(peerHost, peerPorta);
                 InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[8192];
                long totalLido = 0;
                int lido;
                while ((lido = in.read(buffer)) != -1) {
                    totalLido += lido;
                }
                long fim = System.currentTimeMillis();
                System.out.println("[P2P] Download concluído de " + peerHost + ":" + peerPorta + " | Total: " + totalLido + " bytes em " + (fim - inicio) + " ms");
            } catch (Exception e) {
                System.err.println("Erro ao baixar do Peer: " + e.getMessage());
            }
        }
    }
}