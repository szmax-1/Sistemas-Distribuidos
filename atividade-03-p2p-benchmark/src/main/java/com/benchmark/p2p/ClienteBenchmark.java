package com.benchmark.p2p;

import java.net.Socket;
import java.io.InputStream;

public class ClienteBenchmark {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : 8081;

        long inicio = System.currentTimeMillis();
        long bytesLidosTotal = 0;

        try (Socket socket = new Socket(host, porta);
             InputStream in = socket.getInputStream()) {

            byte[] buffer = new byte[8192];
            int bytesLidos;

            // Baixa o fluxo e descarta sem gravar em disco
            while ((bytesLidos = in.read(buffer)) != -1) {
                bytesLidosTotal += bytesLidos;
            }

            long fim = System.currentTimeMillis();
            long tempoTotal = fim - inicio;

            System.out.println("RESULTADO:" + tempoTotal + ":" + bytesLidosTotal);

        } catch (Exception e) {
            System.err.println("Erro durante a medição: " + e.getMessage());
        }
    }
}