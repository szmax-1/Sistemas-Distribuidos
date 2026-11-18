package com.dcomp.drive;

import com.dcomp.drive.Models.*;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.dcomp.drive.SecurityAndLogUtils.*;

public class StorageNodeServer {
    private final DriveConfig cfg;
    private final String nodeId;
    private final String host;
    private final String address;
    private final String controllerUrl;
    private final Map<String, Path> disks = new LinkedHashMap<>();
    private final long simulatedCapacityBytes;

    public StorageNodeServer(DriveConfig cfg) throws IOException {
        this.cfg = cfg;
        this.nodeId = System.getenv().getOrDefault("NODE_ID", "node1");
        this.host = System.getenv().getOrDefault("NODE_HOST", nodeId);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7001"));
        this.address = System.getenv().getOrDefault("NODE_ADDRESS", "http://" + nodeId + ":" + port);
        this.controllerUrl = System.getenv().getOrDefault("CONTROLLER_URL", "http://controller:7000");

        long capMb = Long.parseLong(System.getenv().getOrDefault("SIMULATED_DISK_CAPACITY_MB", "2048"));
        this.simulatedCapacityBytes = capMb * 1024L * 1024L;

        String disksEnv = System.getenv().getOrDefault("DISKS", "disk1:/data/disk1");
        for (String entry : disksEnv.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            Path p = Path.of(parts[1]);
            Files.createDirectories(p);
            disks.put(parts[0], p);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/block", this::handleBlock);
        server.createContext("/verify", this::handleVerify);
        server.createContext("/replicate", this::handleReplicate);
        server.start();

        log("StorageNode:" + nodeId, "STARTED", address, "Host=" + host + ", Disks=" + disks.keySet());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 500, cfg.heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void handleBlock(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String diskId = q.get("diskId");
        String blockId = q.get("blockId");
        Path diskPath = disks.get(diskId);

        if (diskPath == null || blockId == null) {
            sendJson(ex, 400, Map.of("error", "diskId ou blockId invalido"));
            return;
        }

        Path blockFile = diskPath.resolve(blockId + ".blk");
        Path hashFile = diskPath.resolve(blockId + ".sha256");

        switch (ex.getRequestMethod()) {
            case "PUT", "POST" -> {
                byte[] data = ex.getRequestBody().readAllBytes();
                String actualHash = sha256(data);
                String expectedHash = q.get("expectedHash");

                if (expectedHash != null && !expectedHash.isBlank() && !expectedHash.equalsIgnoreCase(actualHash)) {
                    log("StorageNode:" + nodeId, "WRITE_HASH_MISMATCH", blockId,
                            "Expected=" + expectedHash + ", Actual=" + actualHash);
                    sendJson(ex, 400, Map.of("error", "Hash mismatch on write"));
                    return;
                }

                Path tmpFile = diskPath.resolve(blockId + ".tmp");
                Files.write(tmpFile, data);
                Files.move(tmpFile, blockFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.writeString(hashFile, actualHash);

                log("StorageNode:" + nodeId, "BLOCK_WRITTEN", blockId,
                        "Disk=" + diskId + ", Bytes=" + data.length + ", Hash=" + actualHash.substring(0, 12));
                sendJson(ex, 200, Map.of("status", "OK", "storedHash", actualHash, "sizeBytes", data.length));
            }
            case "GET" -> {
                if (!Files.exists(blockFile)) {
                    sendJson(ex, 404, Map.of("error", "Bloco nao encontrado"));
                    return;
                }
                byte[] data = Files.readAllBytes(blockFile);
                log("StorageNode:" + nodeId, "BLOCK_READ", blockId, "Disk=" + diskId + ", Bytes=" + data.length);
                sendBytes(ex, 200, data);
            }
            case "DELETE" -> {
                Files.deleteIfExists(blockFile);
                Files.deleteIfExists(hashFile);
                log("StorageNode:" + nodeId, "BLOCK_DELETED", blockId, "Disk=" + diskId);
                sendJson(ex, 200, Map.of("status", "DELETED"));
            }
            default -> sendJson(ex, 405, Map.of("error", "Metodo nao suportado"));
        }
    }

    private void handleVerify(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String diskId = q.get("diskId");
        String blockId = q.get("blockId");
        String expectedHash = q.get("expectedHash");
        Path diskPath = disks.get(diskId);

        if (diskPath == null || blockId == null) {
            sendJson(ex, 400, Map.of("valid", false, "reason", "INVALID_PARAMS"));
            return;
        }

        Path blockFile = diskPath.resolve(blockId + ".blk");
        if (!Files.exists(blockFile)) {
            sendJson(ex, 200, Map.of("valid", false, "reason", "MISSING"));
            return;
        }

        byte[] data = Files.readAllBytes(blockFile);
        String actualHash = sha256(data);
        if (expectedHash != null && !expectedHash.equalsIgnoreCase(actualHash)) {
            Files.deleteIfExists(blockFile);
            Files.deleteIfExists(diskPath.resolve(blockId + ".sha256"));
            log("StorageNode:" + nodeId, "SCRUB_CORRUPTION_DETECTED", blockId,
                    "Disk=" + diskId + ", Expected=" + expectedHash + ", Actual=" + actualHash);
            sendJson(ex, 200, Map.of("valid", false, "reason", "HASH_MISMATCH"));
            return;
        }

        sendJson(ex, 200, Map.of("valid", true));
    }

    private void handleReplicate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Use POST"));
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes());
        Map<String, String> req = GSON.fromJson(body, Map.class);
        String sourceAddress = req.get("sourceAddress");
        String sourceDiskId = req.get("sourceDiskId");
        String targetDiskId = req.get("targetDiskId");
        String blockId = req.get("blockId");
        String expectedHash = req.get("expectedHash");

        Path targetDisk = disks.get(targetDiskId);
        if (targetDisk == null) {
            sendJson(ex, 400, Map.of("error", "Disco destino invalido"));
            return;
        }

        try {
            String url = sourceAddress + "/block?diskId=" + sourceDiskId + "&blockId=" + blockId;
            HttpRequest httpReq = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() != 200) {
                sendJson(ex, 502, Map.of("error", "Falha ao baixar bloco da origem"));
                return;
            }

            byte[] data = resp.body();
            String actualHash = sha256(data);
            if (expectedHash != null && !expectedHash.equalsIgnoreCase(actualHash)) {
                sendJson(ex, 400, Map.of("error", "Hash invalido durante replicacao"));
                return;
            }

            Path tmpFile = targetDisk.resolve(blockId + ".tmp");
            Path finalFile = targetDisk.resolve(blockId + ".blk");
            Files.write(tmpFile, data);
            Files.move(tmpFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(targetDisk.resolve(blockId + ".sha256"), actualHash);

            log("StorageNode:" + nodeId, "BLOCK_REPLICATED", blockId,
                    "From=" + sourceAddress + " -> Disk=" + targetDiskId);
            sendJson(ex, 200, Map.of("status", "REPLICATED", "storedHash", actualHash));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage()));
        }
    }

    private void sendHeartbeat() {
        try {
            HeartbeatRequest hb = new HeartbeatRequest();
            hb.nodeId = nodeId;
            hb.host = host;
            hb.address = address;
            hb.disks = new ArrayList<>();
            hb.storedBlocks = new ArrayList<>();

            for (Map.Entry<String, Path> entry : disks.entrySet()) {
                String dId = entry.getKey();
                Path dPath = entry.getValue();
                long used = 0;

                try (Stream<Path> files = Files.list(dPath)) {
                    for (Path f : files.toList()) {
                        if (Files.isRegularFile(f)) {
                            used += Files.size(f);
                            String name = f.getFileName().toString();
                            if (name.endsWith(".blk")) {
                                hb.storedBlocks.add(dId + ":" + name.substring(0, name.length() - 4));
                            }
                        }
                    }
                }

                DiskInfo di = new DiskInfo();
                di.diskId = dId;
                di.mountPath = dPath.toString();
                di.totalBytes = simulatedCapacityBytes;
                di.usedBytes = used;
                di.freeBytes = Math.max(0, simulatedCapacityBytes - used);
                hb.disks.add(di);
            }

            HttpResponse<String> resp = postJson(controllerUrl + "/api/heartbeat", hb);
            if (resp.statusCode() == 200) {
                Map<String, Object> parsed = GSON.fromJson(resp.body(), Map.class);
                List<String> obsolete = (List<String>) parsed.get("obsoleteBlocks");
                if (obsolete != null) {
                    for (String item : obsolete) {
                        String[] p = item.split(":", 2);
                        Path dPath = disks.get(p[0]);
                        if (dPath != null) {
                            Files.deleteIfExists(dPath.resolve(p[1] + ".blk"));
                            Files.deleteIfExists(dPath.resolve(p[1] + ".sha256"));
                            log("StorageNode:" + nodeId, "OBSOLETE_BLOCK_PURGED", p[1], "Disk=" + p[0]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("StorageNode:" + nodeId, "HEARTBEAT_FAILED", controllerUrl, e.getMessage());
        }
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                map.put(java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    public static void main(String[] args) throws Exception {
        new StorageNodeServer(DriveConfig.load());
    }
}