package com.benchmark.p2p;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExecutadorBenchmark {

    private static final long SIZE_5MB = 5 * 1024 * 1024L;
    private static final long SIZE_50MB = 50 * 1024 * 1024L;
    private static final long SIZE_500MB = 500 * 1024 * 1024L;

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================================");
        System.out.println("   INICIANDO BATERIA DE EXPERIMENTOS DE DESEMPENHO (CLIENTE-SERVIDOR x P2P)");
        System.out.println("==========================================================================\n");

        long[] tamanhos = {SIZE_5MB, SIZE_50MB, SIZE_500MB};
        String[] nomesTamanhos = {"5MB", "50MB", "500MB"};
        int[] qtdClientes = {1, 5, 10};

        for (int t = 0; t < tamanhos.length; t++) {
            long tamanho = tamanhos[t];
            String nomeTamanho = nomesTamanhos[t];

            for (int clientes : qtdClientes) {
                System.out.println("--------------------------------------------------------------------------");
                System.out.println("EXPERIMENTO: Arquivo = " + nomeTamanho + " | Nós Clientes = " + clientes);
                System.out.println("--------------------------------------------------------------------------");

                executarTesteIterativo(tamanho, clientes);
                executarTesteConcorrente(tamanho, clientes);
                executarTesteThreadPool(tamanho, clientes, 5);
                executarTesteP2P(tamanho, clientes);
                System.out.println();
            }
        }
    }

    private static void executarTesteIterativo(long tamanhoBytes, int numClientes) throws Exception {
        int porta = 9081;
        ServerSocket serverSocket = new ServerSocket(porta);

        Thread serverThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket s = serverSocket.accept()) {
                        GeradorDados.enviarDados(s.getOutputStream(), tamanhoBytes);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
        serverThread.start();

        List<Long> tempos = rodarClientes("Cliente-Servidor (Iterativo - 1 por vez)", "localhost", porta, numClientes);

        serverThread.interrupt();
        serverSocket.close();
        imprimirResultado("Cliente-Servidor (Iterativo)", numClientes, tempos);
    }

    private static void executarTesteConcorrente(long tamanhoBytes, int numClientes) throws Exception {
        int porta = 9082;
        ServerSocket serverSocket = new ServerSocket(porta);

        Thread serverThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try (Socket s = socket) {
                            GeradorDados.enviarDados(s.getOutputStream(), tamanhoBytes);
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception ignored) {}
        });
        serverThread.start();

        List<Long> tempos = rodarClientes("Cliente-Servidor (Thread por Cliente)", "localhost", porta, numClientes);

        serverThread.interrupt();
        serverSocket.close();
        imprimirResultado("Cliente-Servidor (1 Thread/Cliente)", numClientes, tempos);
    }

    private static void executarTesteThreadPool(long tamanhoBytes, int numClientes, int poolSize) throws Exception {
        int porta = 9083;
        ServerSocket serverSocket = new ServerSocket(porta);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        Thread serverThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = serverSocket.accept();
                    pool.execute(() -> {
                        try (Socket s = socket) {
                            GeradorDados.enviarDados(s.getOutputStream(), tamanhoBytes);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
        });
        serverThread.start();

        List<Long> tempos = rodarClientes("Cliente-Servidor (Thread Pool N=" + poolSize + ")", "localhost", porta, numClientes);

        serverThread.interrupt();
        pool.shutdownNow();
        serverSocket.close();
        imprimirResultado("Cliente-Servidor (Thread Pool N=" + poolSize + ")", numClientes, tempos);
    }

    private static void executarTesteP2P(long tamanhoBytes, int numClientes) throws Exception {
        int portaInicial = 9100;
        List<ServerSocket> socketsP2P = new ArrayList<>();

        for (int i = 0; i < numClientes; i++) {
            int portaNo = portaInicial + i;
            ServerSocket ss = new ServerSocket(portaNo);
            socketsP2P.add(ss);
            new Thread(() -> {
                try {
                    while (!ss.isClosed()) {
                        Socket s = ss.accept();
                        new Thread(() -> {
                            try (Socket sock = s) {
                                GeradorDados.enviarDados(sock.getOutputStream(), tamanhoBytes);
                            } catch (Exception ignored) {}
                        }).start();
                    }
                } catch (Exception ignored) {}
            }).start();
        }

        List<Long> tempos = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(numClientes);

        for (int i = 0; i < numClientes; i++) {
            final int index = i;
            executor.execute(() -> {
                int portaAlvo = (index == 0) ? portaInicial : portaInicial + (index - 1);
                long inicio = System.currentTimeMillis();
                try (Socket s = new Socket("localhost", portaAlvo);
                     InputStream in = s.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    while (in.read(buffer) != -1) {}
                    long fim = System.currentTimeMillis();
                    tempos.add(fim - inicio);
                } catch (Exception e) {
                    tempos.add(-1L);
                }
            });
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(10);
        }

        for (ServerSocket ss : socketsP2P) {
            ss.close();
        }

        imprimirResultado("Arquitetura P2P (Malha de Nós)", numClientes, tempos);
    }

    private static List<Long> rodarClientes(String rotulo, String host, int porta, int numClientes) throws Exception {
        List<Long> tempos = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(numClientes);

        for (int i = 0; i < numClientes; i++) {
            executor.execute(() -> {
                long inicio = System.currentTimeMillis();
                try (Socket s = new Socket(host, porta);
                     InputStream in = s.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    while (in.read(buffer) != -1) {}
                    long fim = System.currentTimeMillis();
                    tempos.add(fim - inicio);
                } catch (Exception e) {
                    tempos.add(-1L);
                }
            });
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(10);
        }
        return tempos;
    }

    private static void imprimirResultado(String arquitetura, int numClientes, List<Long> tempos) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long soma = 0;
        int validos = 0;

        for (long t : tempos) {
            if (t >= 0) {
                if (t < min) min = t;
                if (t > max) max = t;
                soma += t;
                validos++;
            }
        }

        long medio = validos > 0 ? soma / validos : 0;
        if (validos == 0) { min = 0; max = 0; }

        System.out.printf("  %-42s | Min: %6d ms | Médio: %6d ms | Máx: %6d ms%n",
                arquitetura, min, medio, max);
    }
}