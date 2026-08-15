package com.votri.bedrockmovementfix;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight asynchronous diagnostic log.
 *
 * It is deliberately independent of Bukkit event processing so diagnostics
 * do not block the server thread on disk I/O.
 */
public final class MovementDiagnosticLogger implements AutoCloseable {
    private final JavaPlugin plugin;
    private final boolean enabled;
    private final Path file;
    private final long maxBytes;
    private final ExecutorService executor;

    public MovementDiagnosticLogger(JavaPlugin plugin, boolean enabled, String fileName, long maxBytes) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.file = plugin.getDataFolder().toPath().resolve(fileName);
        this.maxBytes = Math.max(64 * 1024L, maxBytes);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BedrockMovementFix-Log");
            t.setDaemon(true);
            return t;
        });

        if (enabled) {
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not create diagnostic log directory: " + ex.getMessage());
            }
        }
    }

    public void log(String player, String event) {
        if (!enabled) return;
        String line = Instant.now() + " [" + player + "] " + event + System.lineSeparator();
        executor.execute(() -> append(line));
    }

    private void append(String line) {
        try {
            rotateIfNeeded();
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("Diagnostic log write failed: " + ex.getMessage());
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(file) || Files.size(file) < maxBytes) return;

        Path backup = file.resolveSibling(file.getFileName() + ".1");
        Files.deleteIfExists(backup);
        Files.move(file, backup);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
