package com.votri.bedrockmovementfix;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.destroystokyo.paper.event.player.PlayerFailMoveEvent;

public final class BedrockMovementFixPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("BedrockMovementFix 2.0.0 enabled. Safe correction mode is active.");
    }

    @Override
    public void onDisable() {
        states.clear();
    }

    private void reloadConfigValues() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        debug = getConfig().getBoolean("debug", false);
        bedrockOnly = getConfig().getBoolean("bedrock-only", true);
        bypassPermission = getConfig().getString("bypass-permission", "bedrockmovementfix.bypass");
        failureThreshold = Math.max(1, getConfig().getInt("failure-threshold", 3));
        failureWindowMs = Math.max(100, getConfig().getLong("failure-window-ms", 1000));
        correctionCooldownMs = Math.max(250, getConfig().getLong("correction-cooldown-ms", 1500));
        teleportGraceMs = Math.max(0, getConfig().getLong("teleport-grace-ms", 1200));
        joinGraceMs = Math.max(0, getConfig().getLong("join-grace-ms", 2500));
        maxCorrectionDistance = Math.max(0.1, getConfig().getDouble("max-correction-distance", 1.75));
        ignoreDead = getConfig().getBoolean("ignore-dead", true);
        ignoreSpectator = getConfig().getBoolean("ignore-spectator", true);
        ignoreWorldChange = getConfig().getBoolean("ignore-world-change", true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        State state = new State();
        state.joinAt = System.currentTimeMillis();
        states.put(event.getPlayer().getUniqueId(), state);

        if (debug) {
            getLogger().info("Join: " + event.getPlayer().getName() +
                    " bedrock=" + isBedrock(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        State state = states.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new State());
        state.lastTeleportAt = System.currentTimeMillis();
        state.failures.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        State state = states.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new State());
        state.lastWorldChangeAt = System.currentTimeMillis();
        state.failures.clear();
    }

    /*
     * Critical design rule:
     * We NEVER call event.setAllowed(true) here.
     *
     * Paper remains authoritative over movement validation. We only use
     * PlayerFailMoveEvent as a signal that a Bedrock player may be repeatedly
     * rubber-banded. After enough small failures, we schedule ONE conservative
     * correction to the last known safe position.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFailMove(PlayerFailMoveEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return;
        if (bedrockOnly && !isBedrock(player)) return;
        if (!isEligible(player)) return;

        State state = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        long now = System.currentTimeMillis();

        if (now - state.joinAt < joinGraceMs) return;
        if (now - state.lastTeleportAt < teleportGraceMs) return;
        if (now - state.lastWorldChangeAt < teleportGraceMs) return;
        if (now - state.lastCorrectionAt < correctionCooldownMs) return;

        trim(state.failures, now);
        state.failures.addLast(now);

        if (debug) {
            getLogger().info("FailMove: " + player.getName() +
                    " reason=" + event.getFailReason() +
                    " count=" + state.failures.size());
        }

        if (state.failures.size() < failureThreshold) return;

        Location safe = player.getLocation().clone();
        if (!safe.getWorld().equals(player.getWorld())) return;

        // We only correct very small discrepancies. Large deviations are
        // intentionally left to Paper's own movement handling.
        if (safe.distanceSquared(player.getLocation()) > maxCorrectionDistance * maxCorrectionDistance) {
            state.failures.clear();
            return;
        }

        state.failures.clear();
        state.lastCorrectionAt = now;

        // Re-teleport to the current server-authoritative position on the next
        // tick rather than inside Paper's movement validation event.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !isEligible(player)) return;
            if (System.currentTimeMillis() - state.lastTeleportAt < teleportGraceMs) return;

            player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

            if (debug) {
                getLogger().info("Conservative correction: " + player.getName());
            }
        });
    }

    private boolean isEligible(Player player) {
        if (player.hasPermission(bypassPermission)) return false;
        if (ignoreDead && player.isDead()) return false;
        if (ignoreSpectator && player.getGameMode() == GameMode.SPECTATOR) return false;
        return true;
    }

    /*
     * Floodgate API is deliberately optional at runtime. If Floodgate is not
     * installed, the plugin safely treats the player as Java.
     */
    private boolean isBedrock(Player player) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(api, player.getUniqueId());
            return result instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void trim(Deque<Long> failures, long now) {
        while (!failures.isEmpty() && now - failures.peekFirst() > failureWindowMs) {
            failures.removeFirst();
        }
    }

    private static final class State {
        long joinAt;
        long lastTeleportAt;
        long lastWorldChangeAt;
        long lastCorrectionAt;
        final Deque<Long> failures = new ArrayDeque<>();
    }
}
