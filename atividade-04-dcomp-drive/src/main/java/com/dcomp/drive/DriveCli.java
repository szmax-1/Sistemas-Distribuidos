package com.dcomp.drive;

import com.dcomp.drive.Models.*;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.dcomp.drive.SecurityAndLogUtils.*;

public class DriveCli {
    private static final com.google.gson.Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        DriveConfig cfg = DriveConfig.load();
        String controller = System.getenv().getOrDefault("CONTROLLER_URL", "http://controller:7000");
        String cmd = args[0].toLowerCase();

        switch (cmd) {
            case "put" -> cmdPut(cfg, controller, args[1], args[2]);
            case "get" -> cmdGet(cfg, controller, args[1], args[2]);
            case "rm" -> cmdDelete(controller + "/api/file?path=" + enc(args[1]));
            case "mv" -> cmdPost(controller + "/api/mv", Map.of("src", args[1], "dst", args[2]));
            case "ls" -> cmdGetJson(controller + "/api/ls?path=" + enc(args.length > 1 ? args[1] : "/"));
            case "mkdir" -> cmdPost(controller + "/api/mkdir?path=" + enc(args[1]), Map.of());
            case "rmdir" -> cmdDelete(controller + "/api/rmdir?path=" + enc(args[1]));
            case "stat" -> cmdGetJson(controller + "/api/file?path=" + enc(args[1]));
            case "status" -> cmdGetJson(controller + "/api/status");
            case "drain" -> cmdPost(controller + "/api/nodes/drain?nodeId=" + enc(args[1]), Map.of());
            case "metrics" -> cmdGetJson(controller + "/metrics");
            default -> printUsage();
        }
    }

    private static void cmdPut(DriveConfig cfg, String controller, String localPath, String remotePath) throws Exception {
        long start = System.currentTimeMillis();
        byte[] allBytes = Files.readAllBytes(Path.of(localPath));
        String fileHash = sha256(allBytes);

        List<byte[]> payloads = new ArrayList<>();
        AllocationRequest allocReq = new AllocationRequest();
        allocReq.path = remotePath;
        allocReq.sizeBytes = allBytes.length;
        allocReq.fileHash = fileHash;
        allocReq.encrypted = cfg.encryptionEnabled;
        allocReq.blocks = new ArrayList<>();

        int offset = 0;
        int idx = 0;
        if (allBytes.length == 0) {
            byte[] empty = new byte[0];
            byte[] toSend = cfg.encryptionEnabled ? encryptAesGcm(empty, cfg.encryptionKeyBase64) : empty;
            payloads.add(toSend);
            BlockSpec bs = new BlockSpec();
            bs.index = 0;
            bs.sizeBytes = toSend.length;
            bs.plainHash = sha256(empty);
            bs.storedHash = sha256(toSend);
            allocReq.blocks.add(bs);
        } else {
            while (offset < allBytes.length) {
                int len = Math.min(cfg.blockSizeBytes, allBytes.length - offset);
                byte[] chunk = Arrays.copyOfRange(allBytes, offset, offset + len);
                String plainHash = sha256(chunk);
                byte[] toSend = cfg.encryptionEnabled ? encryptAesGcm(chunk, cfg.encryptionKeyBase64) : chunk;
                String storedHash = sha256(toSend);

                payloads.add(toSend);
                BlockSpec bs = new BlockSpec();
                bs.index = idx++;
                bs.sizeBytes = toSend.length;
                bs.plainHash = plainHash;
                bs.storedHash = storedHash;
                allocReq.blocks.add(bs);
                offset += len;
            }
        }

        // solicita alocação ao controlador
        HttpResponse<String> allocResp = postJson(controller + "/api/allocate", allocReq);
        if (allocResp.statusCode() != 200) {
            System.err.println("Erro na alocacao: " + allocResp.body());
            return;
        }

        Map<String, Object> parsed = GSON.fromJson(allocResp.body(), Map.class);
        FileMetadata meta = GSON.fromJson(GSON.toJson(parsed.get("metadata")), FileMetadata.class);

        //  envia blocos diretamente para os nós de armazenamento em paralelo
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new ArrayList<>();

        for (int i = 0; i < meta.blocks.size(); i++) {
            BlockInfo block = meta.blocks.get(i);
            byte[] data = payloads.get(i);

            for (ReplicaLocation rep : block.replicas) {
                tasks.add(pool.submit(() -> {
                    try {
                        String url = rep.address + "/block?diskId=" + rep.diskId
                                + "&blockId=" + block.blockId + "&expectedHash=" + block.storedHash;
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                                .build();
                        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() == 200) {
                            rep.verified = true;
                        }
                    } catch (Exception e) {
                        System.err.println("Falha ao enviar replica para " + rep.nodeId + ": " + e.getMessage());
                    }
                }));
            }
        }

        for (Future<?> f : tasks) f.get();
        pool.shutdown();

        // confirma commit no controlador
        HttpResponse<String> commitResp = postJson(controller + "/api/commit", meta);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Resposta (" + elapsed + " ms): " + commitResp.body());
    }

    private static void cmdGet(DriveConfig cfg, String controller, String remotePath, String localDest) throws Exception {
        long start = System.currentTimeMillis();
        HttpResponse<String> metaResp = getJson(controller + "/api/file?path=" + enc(remotePath));
        if (metaResp.statusCode() != 200) {
            System.err.println("Erro: " + metaResp.body());
            return;
        }

        FileMetadata meta = GSON.fromJson(metaResp.body(), FileMetadata.class);
        meta.blocks.sort(Comparator.comparingInt(b -> b.index));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (BlockInfo block : meta.blocks) {
            boolean downloaded = false;
            for (ReplicaLocation rep : block.replicas) {
                try {
                    String url = rep.address + "/block?diskId=" + rep.diskId + "&blockId=" + block.blockId;
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                    HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());

                    if (resp.statusCode() == 200) {
                        byte[] raw = resp.body();
                        if (!sha256(raw).equalsIgnoreCase(block.storedHash)) {
                            System.err.println("Aviso: replica corrompida detectada em " + rep.nodeId + ", reportando...");
                            postJson(controller + "/api/report-corrupt", Map.of(
                                    "path", meta.path, "blockId", block.blockId, "nodeId", rep.nodeId, "diskId", rep.diskId));
                            continue;
                        }

                        byte[] plain = meta.encrypted ? decryptAesGcm(raw, cfg.encryptionKeyBase64) : raw;
                        if (!sha256(plain).equalsIgnoreCase(block.plainHash)) {
                            continue;
                        }

                        output.write(plain);
                        downloaded = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!downloaded) {
                System.err.println("Falha critica: nenhuma replica integra disponivel para o bloco " + block.index);
                return;
            }
        }

        byte[] fullFile = output.toByteArray();
        if (!sha256(fullFile).equalsIgnoreCase(meta.fileHash)) {
            System.err.println("Erro de integridade: hash final do arquivo nao confere!");
            return;
        }

        Files.write(Path.of(localDest), fullFile);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Download concluido com integridade SHA-256 verificada (" + fullFile.length + " bytes em " + elapsed + " ms)");
    }

    private static void cmdGetJson(String url) throws Exception {
        HttpResponse<String> resp = getJson(url);
        Object obj = GSON.fromJson(resp.body(), Object.class);
        System.out.println(PRETTY.toJson(obj));
    }

    private static void cmdPost(String url, Object body) throws Exception {
        HttpResponse<String> resp = postJson(url, body);
        Object obj = GSON.fromJson(resp.body(), Object.class);
        System.out.println(PRETTY.toJson(obj));
    }

    private static void cmdDelete(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        Object obj = GSON.fromJson(resp.body(), Object.class);
        System.out.println(PRETTY.toJson(obj));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void printUsage() {
        System.out.println("""
                Uso: DriveCli <comando> [argumentos]
                  put <arquivo_local> <caminho_remoto>
                  get <caminho_remoto> <arquivo_destino>
                  rm <caminho_remoto>
                  mv <origem> <destino>
                  ls [diretorio]
                  mkdir <diretorio>
                  rmdir <diretorio>
                  stat <caminho_remoto>
                  status
                  drain <nodeId>
                  metrics
                """);
    }
}