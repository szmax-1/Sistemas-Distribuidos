package com.dcomp.drive;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;

public class DriveConfig {
    public int replicationFactor = 3;
    public int writeQuorum = 2;
    public int blockSizeBytes = 4 * 1024 * 1024; 
    public long heartbeatIntervalMs = 3000;
    public long heartbeatTimeoutMs = 9000;
    public long scrubbingIntervalMs = 15000;
    public double maxDiskUsagePercent = 90.0;
    public boolean encryptionEnabled = false;
    public String encryptionKeyBase64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    public boolean allowUnderReplicatedWrite = true;
    public int maxConcurrentReplications = 2;

    public static DriveConfig load() {
        DriveConfig cfg = new DriveConfig();
        String path = System.getenv().getOrDefault("CONFIG_PATH", "config/drive-config.json");
        try {
            if (Files.exists(Path.of(path))) {
                String json = Files.readString(Path.of(path));
                cfg = new Gson().fromJson(json, DriveConfig.class);
            }
        } catch (Exception e) {
            System.err.println("Aviso: usando configuracao padrao (" + e.getMessage() + ")");
        }

        if (System.getenv("REPLICATION_FACTOR") != null)
            cfg.replicationFactor = Integer.parseInt(System.getenv("REPLICATION_FACTOR"));
        if (System.getenv("WRITE_QUORUM") != null)
            cfg.writeQuorum = Integer.parseInt(System.getenv("WRITE_QUORUM"));
        if (System.getenv("BLOCK_SIZE_BYTES") != null)
            cfg.blockSizeBytes = Integer.parseInt(System.getenv("BLOCK_SIZE_BYTES"));
        if (System.getenv("ENCRYPTION_ENABLED") != null)
            cfg.encryptionEnabled = Boolean.parseBoolean(System.getenv("ENCRYPTION_ENABLED"));
        if (System.getenv("MAX_DISK_USAGE_PERCENT") != null)
            cfg.maxDiskUsagePercent = Double.parseDouble(System.getenv("MAX_DISK_USAGE_PERCENT"));

        return cfg;
    }
}