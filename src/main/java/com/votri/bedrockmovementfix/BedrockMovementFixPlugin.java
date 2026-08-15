package com.votri.bedrockmovementfix;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockMovementFixPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final EnumSet<Material> specialBlocks = EnumSet.noneOf(Material.class);
    private BukkitTask watchdog;

    private boolean enabled, debug, bedrockOnly, ignoreDead, ignoreSpectator, ignoreVehicles;
    private String bypassPermission;
    private long intervalTicks, failThreshold, failWindowMs;
    private long suspectWindowMs, confirmationWindowMs, progressWindowMs, correlationWindowMs;
    private long activityWindowMs, correctionCooldownMs, joinGraceMs, teleportGraceMs, worldChangeGraceMs;
    private int minimumProgressSamples, minimumStalledSamples, hardStallRequiredSamples;
    private int stallingRequiredSamples, recoveryRequiredSamples;
    private double minimumProgressDistance, hardStallZeroDistance, directionDotThreshold;
    private double specialRadius, maxRestoreDistance;
    private MovementDiagnosticLogger diagnosticLogger;
    private CorrectionMode correctionMode;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        watchdog = Bukkit.getScheduler().runTaskTimer(this, this::watchdogTick, intervalTicks, intervalTicks);
        getLogger().info("BedrockMovementFix 2.1.5 enabled.");
        getLogger().info("Mode: CLIPPED burst + directional server-progress hysteresis.");
    }

    @Override public void onDisable() {
        if (watchdog != null) watchdog.cancel();
        states.clear();
    }

    private void loadConfig() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        debug = getConfig().getBoolean("debug", true);
        bedrockOnly = getConfig().getBoolean("bedrock-only", true);
        ignoreDead = getConfig().getBoolean("ignore-dead", true);
        ignoreSpectator = getConfig().getBoolean("ignore-spectator", true);
        ignoreVehicles = getConfig().getBoolean("ignore-vehicles", true);
        bypassPermission = getConfig().getString("bypass-permission", "bedrockmovementfix.bypass");

        intervalTicks = Math.max(1, getConfig().getLong("watchdog-interval-ticks", 2));
        failThreshold = Math.max(2, getConfig().getLong("fail-move-threshold", 4));
        failWindowMs = Math.max(100, getConfig().getLong("fail-move-window-ms", 1000));
        suspectWindowMs = Math.max(100, getConfig().getLong("suspect-window-ms", 1000));
        confirmationWindowMs = Math.max(100, getConfig().getLong("confirmation-window-ms", 500));
        progressWindowMs = Math.max(100, getConfig().getLong("progress-sample-window-ms", 700));
        correlationWindowMs = Math.max(500, getConfig().getLong("correlation-window-ms", 1500));
        activityWindowMs = Math.max(100, getConfig().getLong("activity-window-ms", 1200));
        correctionCooldownMs = Math.max(500, getConfig().getLong("correction-cooldown-ms", 3000));
        joinGraceMs = Math.max(0, getConfig().getLong("join-grace-ms", 2500));
        teleportGraceMs = Math.max(0, getConfig().getLong("teleport-grace-ms", 1200));
        worldChangeGraceMs = Math.max(0, getConfig().getLong("world-change-grace-ms", 1200));

        minimumProgressDistance = Math.max(0.001, getConfig().getDouble("minimum-progress-distance", 0.08));
        minimumProgressSamples = Math.max(2, getConfig().getInt("minimum-progress-samples", 3));
        minimumStalledSamples = Math.max(2, getConfig().getInt("minimum-stalled-samples", 3));
        hardStallRequiredSamples = Math.max(2, getConfig().getInt("hard-stall-required-samples", 4));
        stallingRequiredSamples = Math.max(2, getConfig().getInt("stalling-required-samples", 3));
        recoveryRequiredSamples = Math.max(1, getConfig().getInt("recovery-required-samples", 2));
        hardStallZeroDistance = Math.max(0.0001, getConfig().getDouble("hard-stall-zero-distance", 0.005));
        directionDotThreshold = Math.max(-1.0, Math.min(1.0, getConfig().getDouble("direction-dot-threshold", 0.25)));
        specialRadius = Math.max(.5, getConfig().getDouble("special-block-radius", 1.75));
        maxRestoreDistance = Math.max(0, getConfig().getDouble("max-restore-distance", 1.5));
        correctionMode = CorrectionMode.from(getConfig().getString("correction-mode", "same-location"));

        specialBlocks.clear();
        for (String name : getConfig().getStringList("special-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material != null) specialBlocks.add(material);
            else getLogger().warning("Unknown special block: " + name);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = now();
        State state = new State();
        state.joinAt = now;
        state.lastMoveAt = now;
        state.lastMeaningfulProgressAt = now;
        state.lastActivityAt = now;
        state.lastAccepted = player.getLocation().clone();
        states.put(player.getUniqueId(), state);
        debug(player, "JOIN bedrock=" + isBedrock(player));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onMove(PlayerMoveEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (!tracked(player)) return;

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        State state = state(player);
        long now = now();

        Location previous = state.lastAccepted;
        double delta = 0.0;
        Vector direction = null;

        if (previous != null && previous.getWorld() != null
                && previous.getWorld().equals(to.getWorld())) {
            Vector displacement = to.toVector().subtract(previous.toVector());
            delta = displacement.length();
            if (delta > 1.0E-5) direction = displacement.normalize();
        }

        if (direction != null) {
            state.lastDirection = direction;
            state.lastDirectionAt = now;
        }

        state.lastAccepted = to.clone();
        state.lastAcceptedAt = now;
        state.lastMoveAt = now;
        state.lastActivityAt = now;

        state.progressSamples.addLast(new MovementSample(
                now, delta, direction == null ? state.lastDirection : direction
        ));
        trimProgressSamples(state, now);

        if (delta >= minimumProgressDistance) {
            state.lastMeaningfulProgressAt = now;
        }

        debug(player, "MOVE delta=" + fmt(delta) + " special=" + nearSpecial(player));
        if (diagnosticLogger != null) diagnosticLogger.log(player.getName(), "MOVE delta=" + fmt(delta) + " special=" + nearSpecial(player));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!tracked(player)) return;

        State state = state(player);
        long now = now();

        state.lastTeleportAt = now;
        state.lastActivityAt = now;
        state.lastMoveAt = now;
        state.lastMeaningfulProgressAt = now;
        state.failures.clear();
        state.progressSamples.clear();
        state.phase = Phase.NORMAL;
        state.hardStallSince = 0L;
        state.consecutiveHardStallSamples = 0;

        if (event.getTo() != null) {
            state.lastAccepted = event.getTo().clone();
            state.lastAcceptedAt = now;
        }

        debug(player, "TELEPORT cause=" + event.getCause());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!tracked(player)) return;

        State state = state(player);
        long now = now();

        state.lastWorldChangeAt = now;
        state.lastActivityAt = now;
        state.lastMoveAt = now;
        state.lastMeaningfulProgressAt = now;
        state.failures.clear();
        state.progressSamples.clear();
        state.phase = Phase.NORMAL;
        state.hardStallSince = 0L;
        state.consecutiveHardStallSamples = 0;
        state.lastAccepted = player.getLocation().clone();
        state.lastAcceptedAt = now;

        debug(player, "WORLD_CHANGE world=" + player.getWorld().getName());
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!tracked(player)) return;
        State state = state(player);
        state.lastActivityAt = now();
        state.interactions++;
        debug(player, "INTERACT action=" + event.getAction() +
                " block=" + (event.getClickedBlock() == null ? "none" : event.getClickedBlock().getType()));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!tracked(player)) return;
        State state = state(player);
        state.lastActivityAt = now();
        state.animations++;
        debug(player, "ANIMATION type=" + event.getAnimationType());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (!tracked(player)) return;

        State state = state(player);
        long now = now();
        String reason = event.getFailReason().name();

        state.lastFailAt = now;
        state.lastActivityAt = now;
        if ("CLIPPED_INTO_BLOCK".equals(reason)) {
            state.lastClippedAt = now;
        }
        state.failures.addLast(new Failure(now, reason));
        trimFailures(state, now);

        debug(player, "FAIL_MOVE reason=" + reason +
                " clippedRecent=" + recentClipped(state, now));
        if (diagnosticLogger != null) {
            diagnosticLogger.log(player.getName(), "FAIL_MOVE reason=" + reason
                    + " clippedRecent=" + recentClipped(state, now));
        }

        // Intentionally never call event.setAllowed(true).
    }

    private void watchdogTick() {
        if (!enabled) return;
        long now = now();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!tracked(player)) continue;
            State state = states.get(player.getUniqueId());
            if (state == null || !eligible(player, state, now)) continue;

            if (!nearSpecial(player)) {
                resetDetection(state);
                continue;
            }

            trimFailures(state, now);
            trimProgressSamples(state, now);

            long clipped = recentClipped(state, now);
            boolean clippedSignal = state.lastClippedAt > 0
                    && now - state.lastClippedAt <= correlationWindowMs;
            boolean hardStall = updateHardStall(state, now);
            boolean directionHeld = hasHeldDirection(state, now);
            boolean tryingToMove = playerStillTryingToMove(state, now);
            boolean recovery = updateRecovery(state, now);
            boolean active = now - state.lastActivityAt <= activityWindowMs;

            // Bidirectional correlation: either signal can open suspicion.
            boolean anySignal = clippedSignal || hardStall;

            switch (state.phase) {
                case NORMAL -> {
                    if (anySignal && active) {
                        state.phase = Phase.SUSPECTED;
                        state.suspectedAt = now;
                        logState(player, state,
                                "NORMAL -> SUSPECTED clipped=" + clipped
                                        + " hardStall=" + hardStall
                                        + " hardSince=" + state.hardStallSince);
                    }
                }

                case SUSPECTED -> {
                    if (!active || recovery) {
                        resetDetection(state);
                        logState(player, state, "SUSPECTED -> NORMAL reason="
                                + (recovery ? "recovery" : "inactive"));
                    } else if (now - state.suspectedAt > correlationWindowMs) {
                        resetDetection(state);
                        logState(player, state, "SUSPECTED -> NORMAL reason=window-expired");
                    } else if (clippedSignal
                            && hardStall
                            && directionHeld
                            && tryingToMove
                            && correlated(state, now)) {
                        state.phase = Phase.STALLING;
                        state.stallingAt = now;
                        state.consecutiveStallingSamples = state.consecutiveHardStallSamples;
                        logState(player, state, "SUSPECTED -> STALLING clipped=" + clipped
                                + " hardSamples=" + state.consecutiveHardStallSamples);
                    }
                }

                case STALLING -> {
                    if (!active || recovery) {
                        resetDetection(state);
                        logState(player, state, "STALLING -> NORMAL reason="
                                + (recovery ? "recovery" : "inactive"));
                    } else if (now - state.stallingAt > correlationWindowMs
                            || !correlated(state, now)
                            || !clippedSignal) {
                        resetDetection(state);
                        logState(player, state, "STALLING -> NORMAL reason=correlation-lost");
                    } else if (hardStall && directionHeld && tryingToMove) {
                        state.consecutiveStallingSamples++;
                        if (state.consecutiveStallingSamples >= stallingRequiredSamples) {
                            state.phase = Phase.CONFIRMED;
                            state.confirmedAt = now;
                            logState(player, state, "STALLING -> CONFIRMED hardSamples="
                                    + state.consecutiveStallingSamples);
                        }
                    }
                }

                case CONFIRMED -> {
                    if (!active || recovery) {
                        resetDetection(state);
                        logState(player, state, "CONFIRMED -> NORMAL reason="
                                + (recovery ? "recovery" : "inactive"));
                    } else if (!correlated(state, now) || !clippedSignal
                            || !hardStall || !directionHeld || !tryingToMove) {
                        resetDetection(state);
                        logState(player, state, "CONFIRMED -> NORMAL reason=confirmation-condition-lost");
                    } else if (now - state.confirmedAt <= correlationWindowMs
                            && now - state.lastCorrectionAt >= correctionCooldownMs) {
                        correctOnce(player, state, now, clipped);
                    }
                }
            }

            debug(player, "WATCH phase=" + state.phase
                    + " clipped=" + clipped
                    + " hardStall=" + hardStall
                    + " hardSamples=" + state.consecutiveHardStallSamples
                    + " hardSince=" + state.hardStallSince
                    + " directionHeld=" + directionHeld
                    + " trying=" + tryingToMove
                    + " recovery=" + recovery);
        }
    }

    private boolean updateHardStall(State state, long now) {
        if (state.progressSamples.isEmpty() || state.lastDirection == null
                || now - state.lastDirectionAt > correlationWindowMs) {
            state.consecutiveHardStallSamples = 0;
            state.hardStallSince = 0L;
            return false;
        }

        int consecutive = 0;
        long since = 0L;
        for (MovementSample sample : state.progressSamples) {
            if (now - sample.time > correlationWindowMs) continue;

            if (sample.delta <= hardStallZeroDistance) {
                if (consecutive == 0) since = sample.time;
                consecutive++;
            } else {
                consecutive = 0;
                since = 0L;
            }
        }

        state.consecutiveHardStallSamples = consecutive;
        state.hardStallSince = consecutive > 0 ? since : 0L;
        return consecutive >= hardStallRequiredSamples;
    }

    private boolean correlated(State state, long now) {
        if (state.lastClippedAt <= 0 || state.hardStallSince <= 0) return false;
        return Math.abs(state.lastClippedAt - state.hardStallSince) <= correlationWindowMs;
    }

    private boolean hasHeldDirection(State state, long now) {
        return state.lastDirection != null
                && now - state.lastDirectionAt <= correlationWindowMs;
    }

    private boolean playerStillTryingToMove(State state, long now) {
        // Zero-delta PlayerMoveEvents still refresh lastMoveAt. This distinguishes
        // an active client repeatedly attempting movement from an inactive player.
        return state.lastMoveAt > 0
                && now - state.lastMoveAt <= Math.min(activityWindowMs, correlationWindowMs)
                && state.lastDirection != null
                && now - state.lastDirectionAt <= correlationWindowMs;
    }

    private boolean updateRecovery(State state, long now) {
        if (state.progressSamples.isEmpty()) {
            state.recoverySamples = 0;
            return false;
        }

        int count = 0;
        for (MovementSample sample : state.progressSamples) {
            if (now - sample.time > confirmationWindowMs) continue;
            if (sample.delta >= minimumProgressDistance) count++;
        }

        state.recoverySamples = count;
        return count >= recoveryRequiredSamples;
    }

    private void resetDetection(State state) {
        state.phase = Phase.NORMAL;
        state.suspectedAt = 0L;
        state.stallingAt = 0L;
        state.confirmedAt = 0L;
        state.hardStallSince = 0L;
        state.consecutiveHardStallSamples = 0;
        state.consecutiveStallingSamples = 0;
        state.recoverySamples = 0;
    }

    private void logState(Player player, State state, String message) {
        if (diagnosticLogger != null) {
            diagnosticLogger.log(player.getName(), "STATE " + message);
        }
    }

    private boolean hasDirectionalPositionStall(State state, long now) {
        trimProgressSamples(state, now);

        if (state.lastDirection == null) return false;
        if (now - state.lastDirectionAt > progressWindowMs) return false;
        if (now - state.lastMeaningfulProgressAt < confirmationWindowMs) return false;
        if (state.progressSamples.size() < minimumStalledSamples) return false;

        int stalled = 0;
        int progress = 0;

        for (MovementSample sample : state.progressSamples) {
            if (sample.direction == null) continue;

            /*
             * Directional progress is measured against the recent movement
             * direction, not against an arbitrary low-delta threshold.
             * A sample is stalled if it barely advances in that direction.
             */
            double directional = sample.delta * Math.max(0.0, sample.direction.dot(state.lastDirection));

            if (directional >= minimumProgressDistance) progress++;
            else stalled++;
        }

        /*
         * We require a sustained lack of meaningful forward progress.
         * A normal acceleration/deceleration sequence can contain several
         * small samples without being classified as a stall.
         */
        return stalled >= minimumStalledSamples && progress == 0;
    }

    private void correctOnce(Player player, State state, long now, long clipped) {
        Location current = player.getLocation();
        Location target = current.clone();

        if (correctionMode == CorrectionMode.LAST_ACCEPTED
                && state.lastAccepted != null
                && state.lastAccepted.getWorld() != null
                && state.lastAccepted.getWorld().equals(current.getWorld())
                && current.distance(state.lastAccepted) <= maxRestoreDistance) {
            target = state.lastAccepted.clone();
            target.setYaw(current.getYaw());
            target.setPitch(current.getPitch());
        }

        final Location correctionTarget = target.clone();
        final long correctionId = ++state.correctionId;

        state.lastCorrectionAt = now;
        state.phase = Phase.NORMAL;

        debug(player, "CORRECTION_ARMED id=" + correctionId +
                " clipped=" + clipped +
                " mode=" + correctionMode +
                " target=" + loc(correctionTarget));
        if (diagnosticLogger != null) {
            diagnosticLogger.log(player.getName(), "CORRECTION_ARMED id=" + correctionId
                    + " clipped=" + clipped + " mode=" + correctionMode
                    + " target=" + loc(correctionTarget));
        }

        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !tracked(player)) return;

            // Exactly one correction. Never schedule a repeating teleport.
            player.teleport(correctionTarget, PlayerTeleportEvent.TeleportCause.PLUGIN);

            long time = now();
            state.lastTeleportAt = time;
            state.lastAccepted = correctionTarget.clone();
            state.lastAcceptedAt = time;
            state.lastMoveAt = time;
            state.lastMeaningfulProgressAt = time;
            state.lastActivityAt = 0L;
            state.failures.clear();
            state.progressSamples.clear();
            state.phase = Phase.NORMAL;

            debug(player, "CORRECTION_SENT id=" + correctionId +
                    " target=" + loc(correctionTarget));
            if (diagnosticLogger != null) {
                diagnosticLogger.log(player.getName(), "CORRECTION_SENT id=" + correctionId
                        + " target=" + loc(correctionTarget));
            }
        });
    }

    private boolean eligible(Player player, State state, long now) {
        if (bypassPermission != null && !bypassPermission.isBlank()
                && player.hasPermission(bypassPermission)) return false;
        if (ignoreDead && player.isDead()) return false;
        if (ignoreSpectator && player.getGameMode() == GameMode.SPECTATOR) return false;
        if (ignoreVehicles && player.isInsideVehicle()) return false;

        return now - state.joinAt >= joinGraceMs
                && now - state.lastTeleportAt >= teleportGraceMs
                && now - state.lastWorldChangeAt >= worldChangeGraceMs;
    }

    private boolean tracked(Player player) {
        return player.isOnline() && (!bedrockOnly || isBedrock(player));
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new State());
    }

    private boolean nearSpecial(Player player) {
        Location location = player.getLocation();
        double radiusSquared = specialRadius * specialRadius;

        for (int x = location.getBlockX() - 2; x <= location.getBlockX() + 2; x++) {
            for (int y = location.getBlockY() - 2; y <= location.getBlockY() + 2; y++) {
                for (int z = location.getBlockZ() - 2; z <= location.getBlockZ() + 2; z++) {
                    Block block = location.getWorld().getBlockAt(x, y, z);
                    if (!specialBlocks.contains(block.getType())) continue;

                    double dx = location.getX() - (x + .5);
                    double dy = location.getY() - (y + .5);
                    double dz = location.getZ() - (z + .5);

                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true;
                }
            }
        }
        return false;
    }

    private long recentClipped(State state, long now) {
        long count = 0;
        for (Failure failure : state.failures) {
            if (now - failure.time <= failWindowMs
                    && "CLIPPED_INTO_BLOCK".equals(failure.reason)) {
                count++;
            }
        }
        return count;
    }

    private void trimFailures(State state, long now) {
        while (!state.failures.isEmpty()
                && now - state.failures.peekFirst().time > failWindowMs) {
            state.failures.removeFirst();
        }
    }

    private void trimProgressSamples(State state, long now) {
        while (!state.progressSamples.isEmpty()
                && now - state.progressSamples.peekFirst().time > progressWindowMs) {
            state.progressSamples.removeFirst();
        }
    }

    private boolean isBedrock(Player player) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(api, player.getUniqueId());
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private void debug(Player player, String message) {
        if (debug) getLogger().info("[DEBUG] " + player.getName() + ": " + message);
    
        if (diagnosticLogger != null) {
            diagnosticLogger.log(player.getName(), "DEBUG " + message);
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String loc(Location location) {
        return String.format(java.util.Locale.ROOT,
                "%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f",
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private enum Phase {
        NORMAL, SUSPECTED, STALLING, CONFIRMED
    }

    private enum CorrectionMode {
        SAME_LOCATION, LAST_ACCEPTED;

        static CorrectionMode from(String value) {
            return "last-accepted".equalsIgnoreCase(value)
                    ? LAST_ACCEPTED : SAME_LOCATION;
        }
    }

    private static final class Failure {
        final long time;
        final String reason;

        Failure(long time, String reason) {
            this.time = time;
            this.reason = reason;
        }
    }

    private static final class MovementSample {
        final long time;
        final double delta;
        final Vector direction;

        MovementSample(long time, double delta, Vector direction) {
            this.time = time;
            this.delta = delta;
            this.direction = direction == null ? null : direction.clone();
        }
    }

    private static final class State {
        long joinAt, lastMoveAt, lastAcceptedAt, lastMeaningfulProgressAt;
        long lastActivityAt, lastTeleportAt, lastWorldChangeAt, lastFailAt, lastCorrectionAt;
        long lastDirectionAt, suspectedAt, stallingAt, confirmedAt, lastClippedAt, hardStallSince;
        int consecutiveHardStallSamples, consecutiveStallingSamples, recoverySamples;
        long interactions, animations, correctionId;

        Location lastAccepted;
        Vector lastDirection;
        Phase phase = Phase.NORMAL;

        final Deque<Failure> failures = new ArrayDeque<>();
        final Deque<MovementSample> progressSamples = new ArrayDeque<>();
    }
}
