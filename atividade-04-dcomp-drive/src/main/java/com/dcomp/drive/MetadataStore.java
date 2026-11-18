package com.dcomp.drive;

import com.dcomp.drive.Models.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MetadataStore {
    private final Path dataDir;
    private final Path walFile;
    private final Path snapshotFile;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private Set<String> directories = new TreeSet<>();
    private Map<String, FileMetadata> files = new ConcurrentHashMap<>();
    private final Map<String, Long> writeLeases = new ConcurrentHashMap<>();

    public MetadataStore(String storageDir) {
        this.dataDir = Path.of(storageDir);
        this.walFile = dataDir.resolve("wal.jsonl");
        this.snapshotFile = dataDir.resolve("snapshot.json");
        try {
            Files.createDirectories(dataDir);
            directories.add("/");
            recover();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao inicializar MetadataStore", e);
        }
    }

    public static String normalize(String path) {
        if (path == null || path.isBlank()) return "/";
        String p = path.trim().replace("\\", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    public static String parentOf(String path) {
        String p = normalize(path);
        if (p.equals("/")) return "/";
        int idx = p.lastIndexOf('/');
        return idx <= 0 ? "/" : p.substring(0, idx);
    }

    public boolean acquireWriteLease(String path) {
        String p = normalize(path);
        long now = System.currentTimeMillis();
        return writeLeases.compute(p, (k, exp) -> (exp == null || exp < now) ? now + 30000L : exp) > now;
    }

    public void releaseWriteLease(String path) {
        writeLeases.remove(normalize(path));
    }

    public boolean mkdir(String path) {
        String p = normalize(path);
        rwLock.writeLock().lock();
        try {
            if (directories.contains(p) || files.containsKey(p)) return false;
            String parent = parentOf(p);
            if (!directories.contains(parent)) return false;
            directories.add(p);
            appendWal("MKDIR", p, null, null);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean rmdir(String path) {
        String p = normalize(path);
        if (p.equals("/")) return false;
        rwLock.writeLock().lock();
        try {
            if (!directories.contains(p)) return false;
            for (String d : directories) {
                if (!d.equals(p) && d.startsWith(p + "/")) return false;
            }
            for (String f : files.keySet()) {
                if (f.startsWith(p + "/")) return false;
            }
            directories.remove(p);
            appendWal("RMDIR", p, null, null);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public List<Object> list(String dirPath) {
        String p = normalize(dirPath);
        rwLock.readLock().lock();
        try {
            if (!directories.contains(p)) return null;
            List<Object> result = new ArrayList<>();
            for (String d : directories) {
                if (!d.equals(p) && parentOf(d).equals(p)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "DIR");
                    item.put("path", d);
                    result.add(item);
                }
            }
            for (FileMetadata f : files.values()) {
                if (parentOf(f.path).equals(p)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "FILE");
                    item.put("path", f.path);
                    item.put("sizeBytes", f.sizeBytes);
                    item.put("health", f.health);
                    result.add(item);
                }
            }
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void commitFile(FileMetadata meta) {
        meta.path = normalize(meta.path);
        rwLock.writeLock().lock();
        try {
            ensureParentDirs(parentOf(meta.path));
            files.put(meta.path, meta);
            appendWal("PUT", meta.path, null, meta);
            saveSnapshot();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void ensureParentDirs(String dir) {
        if (directories.contains(dir)) return;
        String parent = parentOf(dir);
        if (!parent.equals(dir)) ensureParentDirs(parent);
        directories.add(dir);
        appendWal("MKDIR", dir, null, null);
    }

    public FileMetadata getFile(String path) {
        rwLock.readLock().lock();
        try {
            return files.get(normalize(path));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Collection<FileMetadata> allFiles() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(files.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public FileMetadata removeFile(String path) {
        String p = normalize(path);
        rwLock.writeLock().lock();
        try {
            FileMetadata removed = files.remove(p);
            if (removed != null) {
                appendWal("RM", p, null, null);
                saveSnapshot();
            }
            return removed;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean moveFile(String src, String dst) {
        String s = normalize(src);
        String d = normalize(dst);
        rwLock.writeLock().lock();
        try {
            FileMetadata f = files.get(s);
            if (f == null || files.containsKey(d) || directories.contains(d)) return false;
            ensureParentDirs(parentOf(d));
            files.remove(s);
            f.path = d;
            f.updatedAt = System.currentTimeMillis();
            files.put(d, f);
            appendWal("MV", s, d, f);
            saveSnapshot();
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void persistState() {
        rwLock.writeLock().lock();
        try {
            saveSnapshot();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void appendWal(String op, String path, String target, FileMetadata file) {
        try {
            WalEntry entry = new WalEntry();
            entry.op = op;
            entry.path = path;
            entry.targetPath = target;
            entry.file = file;
            entry.timestamp = System.currentTimeMillis();
            String line = SecurityAndLogUtils.GSON.toJson(entry) + "\n";
            Files.writeString(walFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            SecurityAndLogUtils.log("MetadataStore", "WAL_ERROR", path, e.getMessage());
        }
    }

    private void saveSnapshot() {
        try {
            SnapshotData snap = new SnapshotData();
            snap.directories = new ArrayList<>(directories);
            snap.files = new HashMap<>(files);
            Path tmp = dataDir.resolve("snapshot.tmp");
            Files.writeString(tmp, SecurityAndLogUtils.GSON.toJson(snap));
            Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(walFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            SecurityAndLogUtils.log("MetadataStore", "SNAPSHOT_ERROR", "-", e.getMessage());
        }
    }

    private void recover() {
        try {
            if (Files.exists(snapshotFile)) {
                SnapshotData snap = SecurityAndLogUtils.GSON.fromJson(Files.readString(snapshotFile), SnapshotData.class);
                if (snap != null) {
                    if (snap.directories != null) directories = new TreeSet<>(snap.directories);
                    if (snap.files != null) files = new ConcurrentHashMap<>(snap.files);
                }
            }
            if (Files.exists(walFile)) {
                for (String line : Files.readAllLines(walFile)) {
                    if (line.isBlank()) continue;
                    WalEntry e = SecurityAndLogUtils.GSON.fromJson(line, WalEntry.class);
                    switch (e.op) {
                        case "MKDIR" -> directories.add(e.path);
                        case "RMDIR" -> directories.remove(e.path);
                        case "PUT" -> files.put(e.path, e.file);
                        case "RM" -> files.remove(e.path);
                        case "MV" -> {
                            files.remove(e.path);
                            if (e.file != null) files.put(e.targetPath, e.file);
                        }
                    }
                }
            }
            SecurityAndLogUtils.log("MetadataStore", "RECOVERED", "-",
                    "Diretorios=" + directories.size() + ", Arquivos=" + files.size());
        } catch (Exception e) {
            SecurityAndLogUtils.log("MetadataStore", "RECOVERY_ERROR", "-", e.getMessage());
        }
    }

    private static class SnapshotData {
        List<String> directories;
        Map<String, FileMetadata> files;
    }
}