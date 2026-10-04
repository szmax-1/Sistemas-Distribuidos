package com.benchmark.p2p;

import java.io.OutputStream;
import java.io.IOException;

public class GeradorDados {
    public static void enviarDados(OutputStream out, long tamanhoBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long enviados = 0;

        while (enviados < tamanhoBytes) {
            int aEnviar = (int) Math.min(buffer.length, tamanhoBytes - enviados);
            out.write(buffer, 0, aEnviar);
            enviados += aEnviar;
        }
        out.flush();
    }
}