package com.dcomp.drive;

import com.dcomp.drive.Models.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.dcomp.drive.SecurityAndLogUtils.*;

public class ControllerServer {
    private final DriveConfig cfg;
    private final MetadataStore store;
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();
    private final Semaphore replicationLimiter;
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();

    // Métricas de observabilidade (RNF)
    private final AtomicInteger activeReplications = new AtomicInteger(0);
    private final AtomicLong totalCommits = new AtomicLong(0);
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalRecoveries = new AtomicLong(0);
    private final AtomicLong totalCorruptionsDetected = new AtomicLong(0);
    private final AtomicLong lastRecoveryTimeMs = new AtomicLong(0);

    public ControllerServer(DriveConfig cfg) throws IOException {
        this.cfg = cfg;
        String dataDir = System.getenv().getOrDefault("DATA_DIR", "/data/controller");
        this.store = new MetadataStore(dataDir);
        this.replicationLimiter = new Semaphore(cfg.maxConcurrentReplications);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7000"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/heartbeat", this::handleHeartbeat);
        server.createContext("/api/allocate", this::handleAllocate);
        server.createContext("/api/commit", this::handleCommit);
        server.createContext("/api/file", this::handleFile);
        server.createContext("/api/mv", this::handleMove);
        server.createContext("/api/ls", this::handleLs);
        server.createContext("/api/mkdir", this::handleMkdir);
        server.createContext("/api/rmdir", this::handleRmdir);
        server.createContext("/api/report-corrupt", this::handleReportCorrupt);
        server.createContext("/api/nodes/drain", this::handleDrain);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/metrics", this::handleMetrics);
        server.start();

        log("Controller", "STARTED", "port:" + port,
                "N=" + cfg.replicationFactor + ", W=" + cfg.writeQuorum + ", Encryption=" + cfg.encryptionEnabled);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleAtFixedRate(this::monitorNodesAndReplicate, 2000, 2000, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::runScrubbing, cfg.scrubbingIntervalMs, cfg.scrubbingIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void handleHeartbeat(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Use POST"));
            return;
        }

        HeartbeatRequest req = GSON.fromJson(new String(ex.getRequestBody().readAllBytes()), HeartbeatRequest.class);
        NodeInfo existing = nodes.get(req.nodeId);
        boolean isNewOrReturning = (existing == null || existing.state == NodeState.DEAD);

        NodeInfo node = existing != null ? existing : new NodeInfo();
        node.nodeId = req.nodeId;
        node.host = req.host;
        node.address = req.address;
        node.disks = req.disks != null ? req.disks : new ArrayList<>();
        node.lastHeartbeat = System.currentTimeMillis();
        if (node.state != NodeState.DRAINING) {
            node.state = NodeState.ALIVE;
        }
        nodes.put(node.nodeId, node);

        if (isNewOrReturning) {
            log("Controller", "NODE_DISCOVERED_OR_RECOVERED", node.nodeId,
                    "Host=" + node.host + ", Address=" + node.address);
        }

        Set<String> validBlockIds = new HashSet<>();
        for (FileMetadata f : store.allFiles()) {
            for (BlockInfo b : f.blocks) {
                validBlockIds.add(b.blockId);
            }
        }

        List<String> obsoleteBlocks = new ArrayList<>();
        if (req.storedBlocks != null) {
            for (String item : req.storedBlocks) {
                String[] parts = item.split(":", 2);
                if (parts.length == 2 && !validBlockIds.contains(parts[1])) {
                    obsoleteBlocks.add(item);
                }
            }
        }

        sendJson(ex, 200, Map.of("status", "OK", "obsoleteBlocks", obsoleteBlocks));
    }

    private void handleAllocate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Use POST"));
            return;
        }

        AllocationRequest req = GSON.fromJson(new String(ex.getRequestBody().readAllBytes()), AllocationRequest.class);
        String path = MetadataStore.normalize(req.path);

        if (!store.acquireWriteLease(path)) {
            sendJson(ex, 409, Map.of("error", "Arquivo em escrita concorrente (lease ativo): " + path));
            return;
        }

        FileMetadata meta = new FileMetadata();
        meta.path = path;
        meta.sizeBytes = req.sizeBytes;
        meta.createdAt = System.currentTimeMillis();
        meta.updatedAt = meta.createdAt;
        meta.fileHash = req.fileHash;
        meta.encrypted = req.encrypted;
        meta.desiredReplicas = cfg.replicationFactor;

        for (BlockSpec spec : req.blocks) {
            BlockInfo bi = new BlockInfo();
            bi.blockId = UUID.randomUUID().toString();
            bi.index = spec.index;
            bi.sizeBytes = spec.sizeBytes;
            bi.plainHash = spec.plainHash;
            bi.storedHash = spec.storedHash;

            List<ReplicaLocation> targets = selectReplicaTargets(cfg.replicationFactor, spec.sizeBytes, Collections.emptyList());
            if (targets.size() < cfg.replicationFactor && !cfg.allowUnderReplicatedWrite) {
                store.releaseWriteLease(path);
                sendJson(ex, 507, Map.of("error", "Nos/discos insuficientes para replicacao N=" + cfg.replicationFactor
                        + " (disponiveis=" + targets.size() + ")"));
                return;
            }
            bi.replicas.addAll(targets);
            meta.blocks.add(bi);
        }

        log("Controller", "ALLOCATED", path, "Blocks=" + meta.blocks.size() + ", DesiredN=" + cfg.replicationFactor);
        sendJson(ex, 200, Map.of("metadata", meta, "writeQuorum", cfg.writeQuorum));
    }

    private void handleCommit(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Use POST"));
            return;
        }

        FileMetadata meta = GSON.fromJson(new String(ex.getRequestBody().readAllBytes()), FileMetadata.class);
        boolean underReplicated = false;

        for (BlockInfo b : meta.blocks) {
            int assigned = b.replicas.size();
            b.replicas.removeIf(r -> !r.verified);
            int requiredQuorum = Math.min(cfg.writeQuorum, assigned);
            if (b.replicas.size() < requiredQuorum || b.replicas.isEmpty()) {
                store.releaseWriteLease(meta.path);
                log("Controller", "COMMIT_REJECTED_QUORUM", meta.path,
                        "Block=" + b.blockId + ", Verified=" + b.replicas.size() + ", Quorum=" + requiredQuorum);
                sendJson(ex, 400, Map.of("error", "Quorum de escrita nao atingido para o bloco " + b.index));
                return;
            }
            if (b.replicas.size() < meta.desiredReplicas) {
                underReplicated = true;
            }
        }

        meta.health = underReplicated ? FileHealth.UNDER_REPLICATED : FileHealth.HEALTHY;
        meta.updatedAt = System.currentTimeMillis();
        store.commitFile(meta);
        store.releaseWriteLease(meta.path);
        totalCommits.incrementAndGet();

        log("Controller", "FILE_COMMITTED", meta.path, "Health=" + meta.health + ", Size=" + meta.sizeBytes);
        sendJson(ex, 200, Map.of("status", "COMMITTED", "health", meta.health));
    }

    private void handleFile(HttpExchange ex) throws IOException {
        Map<String, String> q = StorageNodeServer.parseQuery(ex.getRequestURI().getRawQuery());
        String path = q.get("path");
        if (path == null) {
            sendJson(ex, 400, Map.of("error", "Parametro path obrigatorio"));
            return;
        }

        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            FileMetadata f = store.getFile(path);
            if (f == null) {
                sendJson(ex, 404, Map.of("error", "Arquivo nao encontrado"));
                return;
            }
            totalReads.incrementAndGet();
            sendJson(ex, 200, f);
        } else if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!store.acquireWriteLease(path)) {
                sendJson(ex, 409, Map.of("error", "Arquivo bloqueado por outra escrita"));
                return;
            }
            try {
                FileMetadata removed = store.removeFile(path);
                if (removed == null) {
                    sendJson(ex, 404, Map.of("error", "Arquivo nao encontrado"));
                    return;
                }
                bgExecutor.submit(() -> {
                    for (BlockInfo b : removed.blocks) {
                        for (ReplicaLocation r : b.replicas) {
                            deleteRemoteBlock(r, b.blockId);
                        }
                    }
                });
                log("Controller", "FILE_DELETED", path, "Blocks=" + removed.blocks.size());
                sendJson(ex, 200, Map.of("status", "DELETED"));
            } finally {
                store.releaseWriteLease(path);
            }
        } else {
            sendJson(ex, 405, Map.of("error", "Metodo nao suportado"));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMove(HttpExchange ex) throws IOException {
        Map<String, String> req = GSON.fromJson(new String(ex.getRequestBody().readAllBytes()), Map.class);
        boolean ok = store.moveFile(req.get("src"), req.get("dst"));
        if (ok) {
            log("Controller", "FILE_MOVED", req.get("src"), "To=" + req.get("dst"));
            sendJson(ex, 200, Map.of("status", "MOVED"));
        } else {
            sendJson(ex, 400, Map.of("error", "Falha ao mover/renomear arquivo"));
        }
    }

    private void handleLs(HttpExchange ex) throws IOException {
        Map<String, String> q = StorageNodeServer.parseQuery(ex.getRequestURI().getRawQuery());
        List<Object> items = store.list(q.getOrDefault("path", "/"));
        if (items == null) {
            sendJson(ex, 404, Map.of("error", "Diretorio nao encontrado"));
            return;
        }
        sendJson(ex, 200, items);
    }

    private void handleMkdir(HttpExchange ex) throws IOException {
        Map<String, String> q = StorageNodeServer.parseQuery(ex.getRequestURI().getRawQuery());
        boolean ok = store.mkdir(q.get("path"));
        if (ok) {
            log("Controller", "MKDIR", q.get("path"), "Diretorio criado");
            sendJson(ex, 200, Map.of("status", "CREATED"));
        } else {
            sendJson(ex, 400, Map.of("error", "Nao foi possivel criar diretorio"));
        }
    }

    private void handleRmdir(HttpExchange ex) throws IOException {
        Map<String, String> q = StorageNodeServer.parseQuery(ex.getRequestURI().getRawQuery());
        boolean ok = store.rmdir(q.get("path"));
        if (ok) {
            log("Controller", "RMDIR", q.get("path"), "Diretorio removido");
            sendJson(ex, 200, Map.of("status", "REMOVED"));
        } else {
            sendJson(ex, 400, Map.of("error", "Diretorio inexistente ou nao vazio"));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReportCorrupt(HttpExchange ex) throws IOException {
        Map<String, String> req = GSON.fromJson(new String(ex.getRequestBody().readAllBytes()), Map.class);
        String path = req.get("path");
        String blockId = req.get("blockId");
        String nodeId = req.get("nodeId");
        String diskId = req.get("diskId");

        FileMetadata f = store.getFile(path);
        if (f != null) {
            for (BlockInfo b : f.blocks) {
                if (b.blockId.equals(blockId)) {
                    b.replicas.removeIf(r -> r.nodeId.equals(nodeId) && r.diskId.equals(diskId));
                    f.health = FileHealth.RECOVERING;
                    totalCorruptionsDetected.incrementAndGet();
                    log("Controller", "CORRUPTION_REPORTED", blockId, "Node=" + nodeId + ", Disk=" + diskId);
                    NodeInfo n = nodes.get(nodeId);
                    if (n != null) {
                        deleteRemoteBlock(new ReplicaLocation(nodeId, n.host, n.address, diskId, false), blockId);
                    }
                }
            }
            store.persistState();
        }
        sendJson(ex, 200, Map.of("status", "RECORDED"));
    }

    private void handleDrain(HttpExchange ex) throws IOException {
        Map<String, String> q = StorageNodeServer.parseQuery(ex.getRequestURI().getRawQuery());
        String nodeId = q.get("nodeId");
        NodeInfo node = nodes.get(nodeId);
        if (node == null) {
            sendJson(ex, 404, Map.of("error", "No nao encontrado"));
            return;
        }
        node.state = NodeState.DRAINING;
        log("Controller", "NODE_DRAIN_STARTED", nodeId, "Migrando replicas para outros nos");
        bgExecutor.submit(this::monitorNodesAndReplicate);
        sendJson(ex, 200, Map.of("status", "DRAINING", "nodeId", nodeId));
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        Map<String, Long> healthCounts = new LinkedHashMap<>();
        for (FileHealth fh : FileHealth.values()) healthCounts.put(fh.name(), 0L);
        for (FileMetadata f : store.allFiles()) {
            healthCounts.merge(f.health.name(), 1L, Long::sum);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodes", nodes.values());
        status.put("filesByHealth", healthCounts);
        status.put("totalFiles", store.allFiles().size());
        status.put("activeReplications", activeReplications.get());
        sendJson(ex, 200, status);
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        long totalReplicas = 0;
        for (FileMetadata f : store.allFiles()) {
            for (BlockInfo b : f.blocks) totalReplicas += b.replicas.size();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalFiles", store.allFiles().size());
        m.put("totalReplicas", totalReplicas);
        m.put("totalCommits", totalCommits.get());
        m.put("totalReads", totalReads.get());
        m.put("totalRecoveries", totalRecoveries.get());
        m.put("totalCorruptionsDetected", totalCorruptionsDetected.get());
        m.put("lastRecoveryTimeMs", lastRecoveryTimeMs.get());
        m.put("activeReplications", activeReplications.get());
        m.put("aliveNodes", nodes.values().stream().filter(n -> n.state == NodeState.ALIVE).count());
        m.put("deadNodes", nodes.values().stream().filter(n -> n.state == NodeState.DEAD).count());
        sendJson(ex, 200, m);
    }

    private List<ReplicaLocation> selectReplicaTargets(int count, long requiredBytes, List<ReplicaLocation> existing) {
        Set<String> usedHosts = new HashSet<>();
        Set<String> usedNodeDisks = new HashSet<>();
        for (ReplicaLocation r : existing) {
            usedHosts.add(r.host);
            usedNodeDisks.add(r.nodeId + ":" + r.diskId);
        }

        record Candidate(NodeInfo node, DiskInfo disk) {}
        List<Candidate> candidates = new ArrayList<>();

        for (NodeInfo n : nodes.values()) {
            if (n.state != NodeState.ALIVE) continue;
            for (DiskInfo d : n.disks) {
                if (d.usagePercent() >= cfg.maxDiskUsagePercent) continue;
                if (d.freeBytes < requiredBytes) continue;
                if (usedNodeDisks.contains(n.nodeId + ":" + d.diskId)) continue;
                candidates.add(new Candidate(n, d));
            }
        }

        candidates.sort((a, b) -> Long.compare(b.disk.freeBytes, a.disk.freeBytes));

        List<ReplicaLocation> chosen = new ArrayList<>();
        for (Candidate c : candidates) {
            if (chosen.size() >= count) break;
            if (!usedHosts.contains(c.node.host) && !usedNodeDisks.contains(c.node.nodeId + ":" + c.disk.diskId)) {
                usedHosts.add(c.node.host);
                usedNodeDisks.add(c.node.nodeId + ":" + c.disk.diskId);
                chosen.add(new ReplicaLocation(c.node.nodeId, c.node.host, c.node.address, c.disk.diskId, false));
            }
        }

        if (chosen.size() < count) {
            for (Candidate c : candidates) {
                if (chosen.size() >= count) break;
                String key = c.node.nodeId + ":" + c.disk.diskId;
                if (!usedNodeDisks.contains(key)) {
                    usedNodeDisks.add(key);
                    chosen.add(new ReplicaLocation(c.node.nodeId, c.node.host, c.node.address, c.disk.diskId, false));
                }
            }
        }
        return chosen;
    }

    private void monitorNodesAndReplicate() {
        long now = System.currentTimeMillis();
        for (NodeInfo n : nodes.values()) {
            if (n.state == NodeState.DRAINING) continue;
            long elapsed = now - n.lastHeartbeat;
            if (elapsed > cfg.heartbeatTimeoutMs && n.state != NodeState.DEAD) {
                n.state = NodeState.DEAD;
                log("Controller", "NODE_DEAD_DETECTED", n.nodeId, "ElapsedMs=" + elapsed);
            } else if (elapsed > cfg.heartbeatIntervalMs * 2 && elapsed <= cfg.heartbeatTimeoutMs && n.state == NodeState.ALIVE) {
                n.state = NodeState.SUSPECT;
                log("Controller", "NODE_SUSPECT", n.nodeId, "ElapsedMs=" + elapsed);
            }
        }

        List<FileMetadata> files = new ArrayList<>(store.allFiles());
        files.sort(Comparator.comparingInt(this::minLiveReplicas));

        for (FileMetadata f : files) {
            boolean changed = false;
            boolean allHealthy = true;

            for (BlockInfo b : f.blocks) {
                boolean removedDead = b.replicas.removeIf(r -> {
                    NodeInfo n = nodes.get(r.nodeId);
                    return n == null || n.state == NodeState.DEAD;
                });
                if (removedDead) changed = true;

                List<ReplicaLocation> activeSourceReplicas = b.replicas.stream()
                        .filter(r -> {
                            NodeInfo n = nodes.get(r.nodeId);
                            return n != null && n.state == NodeState.ALIVE && r.verified;
                        }).toList();

                int missing = f.desiredReplicas - activeSourceReplicas.size();
                if (missing > 0) {
                    allHealthy = false;
                    f.health = FileHealth.RECOVERING;

                    if (!activeSourceReplicas.isEmpty()) {
                        List<ReplicaLocation> newTargets = selectReplicaTargets(missing, b.sizeBytes, b.replicas);
                        ReplicaLocation source = activeSourceReplicas.get(0);

                        for (ReplicaLocation target : newTargets) {
                            if (replicationLimiter.tryAcquire()) {
                                activeReplications.incrementAndGet();
                                bgExecutor.submit(() -> {
                                    long startRec = System.currentTimeMillis();
                                    try {
                                        Map<String, String> payload = Map.of(
                                                "sourceAddress", source.address,
                                                "sourceDiskId", source.diskId,
                                                "targetDiskId", target.diskId,
                                                "blockId", b.blockId,
                                                "expectedHash", b.storedHash
                                        );
                                        HttpResponse<String> resp = postJson(target.address + "/replicate", payload);
                                        if (resp.statusCode() == 200) {
                                            target.verified = true;
                                            b.replicas.add(target);
                                            totalRecoveries.incrementAndGet();
                                            lastRecoveryTimeMs.set(System.currentTimeMillis() - startRec);
                                            log("Controller", "REPLICATION_COMPLETED", b.blockId,
                                                    "File=" + f.path + ", Target=" + target.nodeId + ":" + target.diskId);
                                        }
                                    } catch (Exception e) {
                                        log("Controller", "REPLICATION_FAILED", b.blockId, e.getMessage());
                                    } finally {
                                        activeReplications.decrementAndGet();
                                        replicationLimiter.release();
                                        updateFileHealthAndPersist(f);
                                    }
                                });
                            }
                        }
                    }
                }

                if (activeSourceReplicas.size() >= f.desiredReplicas) {
                    Iterator<ReplicaLocation> it = b.replicas.iterator();
                    while (it.hasNext()) {
                        ReplicaLocation r = it.next();
                        NodeInfo n = nodes.get(r.nodeId);
                        if (n != null && n.state == NodeState.DRAINING) {
                            deleteRemoteBlock(r, b.blockId);
                            it.remove();
                            changed = true;
                            log("Controller", "DRAIN_BLOCK_EVICTED", b.blockId, "Node=" + r.nodeId);
                        }
                    }
                }
            }

            if (allHealthy && f.health != FileHealth.HEALTHY) {
                f.health = FileHealth.HEALTHY;
                changed = true;
            }
            if (changed) store.persistState();
        }
    }

    private void updateFileHealthAndPersist(FileMetadata f) {
        boolean healthy = true;
        for (BlockInfo b : f.blocks) {
            long live = b.replicas.stream().filter(r -> {
                NodeInfo n = nodes.get(r.nodeId);
                return n != null && n.state == NodeState.ALIVE && r.verified;
            }).count();
            if (live < f.desiredReplicas) healthy = false;
        }
        f.health = healthy ? FileHealth.HEALTHY : FileHealth.UNDER_REPLICATED;
        store.persistState();
    }

    private int minLiveReplicas(FileMetadata f) {
        int min = Integer.MAX_VALUE;
        for (BlockInfo b : f.blocks) {
            int count = (int) b.replicas.stream().filter(r -> {
                NodeInfo n = nodes.get(r.nodeId);
                return n != null && n.state == NodeState.ALIVE && r.verified;
            }).count();
            if (count < min) min = count;
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    @SuppressWarnings("unchecked")
    private void runScrubbing() {
        for (FileMetadata f : store.allFiles()) {
            boolean changed = false;
            for (BlockInfo b : f.blocks) {
                Iterator<ReplicaLocation> it = b.replicas.iterator();
                while (it.hasNext()) {
                    ReplicaLocation r = it.next();
                    NodeInfo n = nodes.get(r.nodeId);
                    if (n == null || n.state != NodeState.ALIVE) continue;

                    try {
                        String url = n.address + "/verify?diskId=" + r.diskId + "&blockId=" + b.blockId + "&expectedHash=" + b.storedHash;
                        HttpResponse<String> resp = getJson(url);
                        Map<String, Object> res = GSON.fromJson(resp.body(), Map.class);
                        if (Boolean.FALSE.equals(res.get("valid"))) {
                            it.remove();
                            f.health = FileHealth.RECOVERING;
                            totalCorruptionsDetected.incrementAndGet();
                            changed = true;
                            log("Controller", "SCRUB_REPLICA_REMOVED", b.blockId,
                                    "File=" + f.path + ", Node=" + r.nodeId + ":" + r.diskId);
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (changed) {
                store.persistState();
                bgExecutor.submit(this::monitorNodesAndReplicate);
            }
        }
    }

    private void deleteRemoteBlock(ReplicaLocation r, String blockId) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(r.address + "/block?diskId=" + r.diskId + "&blockId=" + blockId))
                    .DELETE().build();
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    public static void main(String[] args) throws Exception {
        new ControllerServer(DriveConfig.load());
    }
}