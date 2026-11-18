package com.dcomp.drive;

import java.util.*;

public class Models {

    public enum NodeState { ALIVE, SUSPECT, DEAD, DRAINING }
    public enum FileHealth { HEALTHY, UNDER_REPLICATED, RECOVERING, CORRUPTED }

    public static class DiskInfo {
        public String diskId;
        public String mountPath;
        public long totalBytes;
        public long usedBytes;
        public long freeBytes;

        public double usagePercent() {
            return totalBytes <= 0 ? 0.0 : (usedBytes * 100.0) / totalBytes;
        }
    }

    public static class NodeInfo {
        public String nodeId;
        public String host; 
        public String address; 
        public NodeState state = NodeState.ALIVE;
        public long lastHeartbeat;
        public List<DiskInfo> disks = new ArrayList<>();
    }

    public static class ReplicaLocation {
        public String nodeId;
        public String host;
        public String address;
        public String diskId;
        public boolean verified;

        public ReplicaLocation() {}
        public ReplicaLocation(String nodeId, String host, String address, String diskId, boolean verified) {
            this.nodeId = nodeId;
            this.host = host;
            this.address = address;
            this.diskId = diskId;
            this.verified = verified;
        }
    }

    public static class BlockInfo {
        public String blockId;
        public int index;
        public long sizeBytes;
        public String plainHash;  
        public String storedHash; 
        public List<ReplicaLocation> replicas = new ArrayList<>();
    }

    public static class FileMetadata {
        public String path;
        public long sizeBytes;
        public long createdAt;
        public long updatedAt;
        public String fileHash; 
        public boolean encrypted;
        public int desiredReplicas;
        public FileHealth health = FileHealth.HEALTHY;
        public List<BlockInfo> blocks = new ArrayList<>();
    }

    public static class HeartbeatRequest {
        public String nodeId;
        public String host;
        public String address;
        public List<DiskInfo> disks;
        public List<String> storedBlocks; 
    }

    public static class AllocationRequest {
        public String path;
        public long sizeBytes;
        public String fileHash;
        public boolean encrypted;
        public List<BlockSpec> blocks;
    }

    public static class BlockSpec {
        public int index;
        public long sizeBytes;
        public String plainHash;
        public String storedHash;
    }

    public static class WalEntry {
        public String op; 
        public String path;
        public String targetPath;
        public FileMetadata file;
        public long timestamp;
    }
}