package com.votri.bedrockmovementfix;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MovementDiagnosticLogger implements AutoCloseable {

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final Path file;
    private final long maxBytes;
    private final ExecutorService executor;

    public MovementDiagnosticLogger(
            JavaPlugin plugin,
            boolean enabled,
            String fileName,
            long maxBytes
    ) {
        this.plugin = plugin;
        this.enabled = enabled;

        String safeName = (fileName == null || fileName.isBlank())
                ? "bedrock-movement-fix.log"
                : fileName;

        this.file = plugin.getDataFolder().toPath().resolve(safeName);
        this.maxBytes = Math.max(64 * 1024L, maxBytes);

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(
                    r,
                    "BedrockMovementFix-DiagnosticLogger"
            );
            thread.setDaemon(true);
            return thread;
        });

        if (enabled) {
            try {
                Files.createDirectories(file.getParent());

                // Tạo file ngay khi plugin bật.
                if (!Files.exists(file)) {
                    Files.createFile(file);
                }

            } catch (IOException ex) {
                plugin.getLogger().warning(
                        "Cannot initialize diagnostic log: "
                                + ex.getMessage()
                );
            }
        }
    }

    public void log(String player, String event) {
        if (!enabled) {
            return;
        }

        String safePlayer = player == null ? "UNKNOWN" : player;

        String line =
                Instant.now()
                        + " ["
                        + safePlayer
                        + "] "
                        + event
                        + System.lineSeparator();

        try {
            executor.execute(() -> append(line));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(
                    "Diagnostic logger queue failed: "
                            + ex.getMessage()
            );
        }
    }

    private void append(String line) {
        try {
            rotateIfNeeded();

            Files.writeString(
                    file,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException ex) {
            plugin.getLogger().warning(
                    "Diagnostic log write failed: "
                            + ex.getMessage()
            );
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(file)) {
            return;
        }

        if (Files.size(file) < maxBytes) {
            return;
        }

        Path backup = file.resolveSibling(
                file.getFileName() + ".1"
        );

        Files.deleteIfExists(backup);
        Files.move(file, backup);

        Files.createFile(file);
    }

    public Path getFile() {
        return file;
    }

    @Override
    public void close() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}