package com.votri.bedrockmovementfix;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockMovementFixPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private boolean enabled;
    private boolean debug;
    private boolean bedrockOnly;
    private String bypassPermission;

    private int failureThreshold;
    private long failureWindowMs;
    private long correctionCooldownMs;
    private long teleportGraceMs;
    private long joinGraceMs;
    private double maxCorrectionDistance;

    private boolean ignoreDead;
    private boolean ignoreSpectator;
    private boolean ignoreWorldChange;

    private boolean handleMovedTooQuickly;
    private boolean handleMovedWrongly;
    private boolean handleClippedIntoBlock;
    private boolean handleMovedIntoUnloadedChunk;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("BedrockMovementFix 2.0.0 enabled.");
        getLogger().info("Paper movement validation remains authoritative; failed movement is never force-allowed.");
    }

    @Override
    public void onDisable() {
        states.clear();
    }

    private void loadSettings() {
        reloadConfig();

        enabled = getConfig().getBoolean("enabled", true);
        debug = getConfig().getBoolean("debug", false);
        bedrockOnly = getConfig().getBoolean("bedrock-only", true);

        bypassPermission = getConfig().getString(
                "bypass-permission",
                "bedrockmovementfix.bypass"
        );

        failureThreshold = Math.max(
                1,
                getConfig().getInt("failure-threshold", 3)
        );

        failureWindowMs = Math.max(
                100L,
                getConfig().getLong("failure-window-ms", 1000L)
        );

        correctionCooldownMs = Math.max(
                250L,
                getConfig().getLong("correction-cooldown-ms", 1500L)
        );

        teleportGraceMs = Math.max(
                0L,
                getConfig().getLong("teleport-grace-ms", 1200L)
        );

        joinGraceMs = Math.max(
                0L,
                getConfig().getLong("join-grace-ms", 2500L)
        );

        maxCorrectionDistance = Math.max(
                0.1D,
                getConfig().getDouble("max-correction-distance", 1.75D)
        );

        ignoreDead = getConfig().getBoolean("ignore-dead", true);
        ignoreSpectator = getConfig().getBoolean("ignore-spectator", true);
        ignoreWorldChange = getConfig().getBoolean("ignore-world-change", true);

        handleMovedTooQuickly =
                getConfig().getBoolean("handle.moved-too-quickly", true);

        handleMovedWrongly =
                getConfig().getBoolean("handle.moved-wrongly", true);

        handleClippedIntoBlock =
                getConfig().getBoolean("handle.clipped-into-block", false);

        handleMovedIntoUnloadedChunk =
                getConfig().getBoolean("handle.moved-into-unloaded-chunk", false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        PlayerState state = new PlayerState();
        state.joinAt = now();
        state.lastAcceptedLocation = player.getLocation().clone();

        states.put(player.getUniqueId(), state);

        debug(player, "joined; bedrock=" + isBedrock(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /*
     * Only accepted movement is stored as the last known good server position.
     * This is fundamentally different from the old implementation, which
     * accepted failed movement.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedMove(PlayerMoveEvent event) {
        if (!enabled) return;
        if (event instanceof PlayerTeleportEvent) return;

        Player player = event.getPlayer();
        if (!player.isOnline()) return;
        if (bedrockOnly && !isBedrock(player)) return;
        if (isBypassed(player)) return;
        if (!isEligible(player)) return;

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(),
                key -> new PlayerState()
        );

        state.lastAcceptedLocation = to.clone();
        state.lastAcceptedAt = now();

        // Successful movement means the failure burst has recovered.
        trimFailures(state.failures, state.lastAcceptedAt);
        if (!state.failures.isEmpty()) {
            state.failures.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(),
                key -> new PlayerState()
        );

        long now = now();

        state.lastTeleportAt = now;
        state.failures.clear();

        if (event.getTo() != null) {
            state.lastAcceptedLocation = event.getTo().clone();
            state.lastAcceptedAt = now;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(),
                key -> new PlayerState()
        );

        long now = now();

        state.lastWorldChangeAt = now;
        state.failures.clear();
        state.lastAcceptedLocation = player.getLocation().clone();
        state.lastAcceptedAt = now;
    }

    /*
     * Paper 26.1.2 exposes four concrete failure reasons:
     * MOVED_TOO_QUICKLY
     * MOVED_WRONGLY
     * CLIPPED_INTO_BLOCK
     * MOVED_INTO_UNLOADED_CHUNK
     *
     * We observe this event only. We NEVER call setAllowed(true).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return;

        if (bedrockOnly && !isBedrock(player)) return;
        if (isBypassed(player)) return;
        if (!isEligible(player)) return;
        if (!isHandled(event.getFailReason())) return;

        PlayerState state = states.computeIfAbsent(
                player.getUniqueId(),
                key -> new PlayerState()
        );

        long now = now();

        if (now - state.joinAt < joinGraceMs) return;
        if (now - state.lastTeleportAt < teleportGraceMs) return;
        if (now - state.lastWorldChangeAt < teleportGraceMs) return;
        if (now - state.lastCorrectionAt < correctionCooldownMs) return;

        trimFailures(state.failures, now);
        state.failures.addLast(now);

        Location safe = state.lastAcceptedLocation;
        Location attempted = event.getTo();

        if (safe == null || attempted == null) return;
        if (safe.getWorld() == null || attempted.getWorld() == null) return;

        if (!safe.getWorld().equals(attempted.getWorld())) {
            state.failures.clear();
            return;
        }

        double distanceSquared = safe.distanceSquared(attempted);
        double maxDistanceSquared =
                maxCorrectionDistance * maxCorrectionDistance;

        debug(
                player,
                "fail=" + event.getFailReason()
                        + " failures=" + state.failures.size()
                        + " attemptedDelta=" + Math.sqrt(distanceSquared)
        );

        if (distanceSquared > maxDistanceSquared) {
            // A large discrepancy is deliberately left to Paper.
            state.failures.clear();
            return;
        }

        if (state.failures.size() < failureThreshold) return;

        Location correction = safe.clone();

        state.failures.clear();
        state.lastCorrectionAt = now;

        /*
         * Never teleport from inside PlayerFailMoveEvent.
         * Schedule it for the next tick, after Paper has completed its own
         * movement handling.
         */
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            if (isBypassed(player)) return;
            if (!isEligible(player)) return;

            long current = now();

            if (current - state.lastTeleportAt < teleportGraceMs) return;
            if (current - state.lastWorldChangeAt < teleportGraceMs) return;

            Location currentLocation = player.getLocation();

            if (!currentLocation.getWorld().equals(correction.getWorld())) return;

            /*
             * If Paper has already corrected the player, do nothing.
             * This prevents duplicate correction packets.
             */
            if (currentLocation.distanceSquared(correction) < 0.0001D) {
                return;
            }

            player.teleport(
                    correction,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            state.lastTeleportAt = current;
            state.lastAcceptedLocation = correction.clone();
            state.lastAcceptedAt = current;

            debug(player, "conservative correction applied");
        });
    }

    private boolean isHandled(PlayerFailMoveEvent.FailReason reason) {
        return switch (reason) {
            case MOVED_TOO_QUICKLY -> handleMovedTooQuickly;
            case MOVED_WRONGLY -> handleMovedWrongly;
            case CLIPPED_INTO_BLOCK -> handleClippedIntoBlock;
            case MOVED_INTO_UNLOADED_CHUNK -> handleMovedIntoUnloadedChunk;
        };
    }

    private boolean isEligible(Player player) {
        if (ignoreDead && player.isDead()) return false;

        if (ignoreSpectator
                && player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        return true;
    }

    private boolean isBypassed(Player player) {
        return bypassPermission != null
                && !bypassPermission.isBlank()
                && player.hasPermission(bypassPermission);
    }

    /*
     * Floodgate is intentionally detected at runtime so this plugin does not
     * require a compile-time Floodgate dependency. The backend must still have
     * Floodgate installed/configured for Bedrock detection to work.
     */
    private boolean isBedrock(Player player) {
        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi"
            );

            Object api = apiClass
                    .getMethod("getInstance")
                    .invoke(null);

            Object result = apiClass
                    .getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(api, player.getUniqueId());

            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException ignored) {
            return false;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private void trimFailures(Deque<Long> failures, long now) {
        while (!failures.isEmpty()
                && now - failures.peekFirst() > failureWindowMs) {
            failures.removeFirst();
        }
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private void debug(Player player, String message) {
        if (!debug) return;

        getLogger().info(
                "[DEBUG] " + player.getName() + ": " + message
        );
    }

    private static final class PlayerState {
        long joinAt;
        long lastTeleportAt;
        long lastWorldChangeAt;
        long lastCorrectionAt;
        long lastAcceptedAt;

        Location lastAcceptedLocation;

        final Deque<Long> failures = new ArrayDeque<>();
    }
}
