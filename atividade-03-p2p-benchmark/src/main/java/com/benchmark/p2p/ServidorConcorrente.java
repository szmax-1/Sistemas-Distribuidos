package com.benchmark.p2p;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.OutputStream;

public class ServidorConcorrente {
    public static final int PORTA = 8082;

    public static void main(String[] args) throws Exception {
        long tamanhoArquivo = args.length > 0 ? Long.parseLong(args[0]) : 50 * 1024 * 1024L;

        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("Servidor Concorrente (Thread/Cliente) rodando na porta " + PORTA);

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> {
                    try (Socket s = socket; OutputStream out = s.getOutputStream()) {
                        GeradorDados.enviarDados(out, tamanhoArquivo);
                    } catch (Exception e) {
                        System.err.println("Erro na thread do cliente: " + e.getMessage());
                    }
                }).start();
            }
        }
    }
}