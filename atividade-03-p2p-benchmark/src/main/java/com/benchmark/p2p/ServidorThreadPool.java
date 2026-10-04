package com.benchmark.p2p;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServidorThreadPool {
    public static final int PORTA = 8083;

    public static void main(String[] args) throws Exception {
        long tamanhoArquivo = args.length > 0 ? Long.parseLong(args[0]) : 50 * 1024 * 1024L;
        int maxThreads = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        ExecutorService threadPool = Executors.newFixedThreadPool(maxThreads);

        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("Servidor Thread Pool (N=" + maxThreads + ") rodando na porta " + PORTA);

            while (true) {
                Socket socket = serverSocket.accept();
                threadPool.execute(() -> {
                    try (Socket s = socket; OutputStream out = s.getOutputStream()) {
                        GeradorDados.enviarDados(out, tamanhoArquivo);
                    } catch (Exception e) {
                        System.err.println("Erro no worker do pool: " + e.getMessage());
                    }
                });
            }
        }
    }
}