package com.benchmark.p2p;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.OutputStream;

public class ServidorIterativo {
    public static final int PORTA = 8081;

    public static void main(String[] args) throws Exception {
        long tamanhoArquivo = args.length > 0 ? Long.parseLong(args[0]) : 50 * 1024 * 1024L;
        
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("Servidor Iterativo rodando na porta " + PORTA);

            while (true) {
                try (Socket socket = serverSocket.accept();
                     OutputStream out = socket.getOutputStream()) {
                    GeradorDados.enviarDados(out, tamanhoArquivo);
                } catch (Exception e) {
                    System.err.println("Erro na conexão: " + e.getMessage());
                }
            }
        }
    }
}