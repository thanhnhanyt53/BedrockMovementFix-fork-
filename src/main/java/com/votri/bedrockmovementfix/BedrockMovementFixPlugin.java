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
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockMovementFixPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final EnumSet<Material> specialBlocks = EnumSet.noneOf(Material.class);

    private boolean enabled;
    private boolean debug;
    private boolean bedrockOnly;
    private boolean handleFailMove;
    private boolean ignoreDead;
    private boolean ignoreSpectator;
    private boolean ignoreVehicles;
    private String bypassPermission;

    private long stallMs;
    private long activityWindowMs;
    private long correctionCooldownMs;
    private long joinGraceMs;
    private long teleportGraceMs;
    private long worldChangeGraceMs;
    private long watchdogIntervalTicks;

    private double specialBlockRadius;
    private double maxRestoreDistance;
    private CorrectionMode correctionMode;

    private BukkitTask watchdogTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        Bukkit.getPluginManager().registerEvents(this, this);

        watchdogTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::watchdogTick,
                watchdogIntervalTicks,
                watchdogIntervalTicks
        );

        getLogger().info("BedrockMovementFix 2.1.0 enabled.");
        getLogger().info("Watchdog mode: no ProtocolLib/PacketEvents dependency.");
        getLogger().info("PlayerFailMoveEvent is observed only; failed movement is never force-allowed.");
    }

    @Override
    public void onDisable() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        states.clear();
    }

    private void loadConfigValues() {
        reloadConfig();

        enabled = getConfig().getBoolean("enabled", true);
        debug = getConfig().getBoolean("debug", true);
        bedrockOnly = getConfig().getBoolean("bedrock-only", true);
        handleFailMove = getConfig().getBoolean("handle-fail-move", true);

        bypassPermission = getConfig().getString(
                "bypass-permission",
                "bedrockmovementfix.bypass"
        );

        watchdogIntervalTicks = Math.max(
                1L,
                getConfig().getLong("watchdog-interval-ticks", 5L)
        );

        stallMs = Math.max(
                100L,
                getConfig().getLong("stall-ms", 700L)
        );

        activityWindowMs = Math.max(
                100L,
                getConfig().getLong("activity-window-ms", 900L)
        );

        correctionCooldownMs = Math.max(
                250L,
                getConfig().getLong("correction-cooldown-ms", 3000L)
        );

        joinGraceMs = Math.max(
                0L,
                getConfig().getLong("join-grace-ms", 2500L)
        );

        teleportGraceMs = Math.max(
                0L,
                getConfig().getLong("teleport-grace-ms", 1200L)
        );

        worldChangeGraceMs = Math.max(
                0L,
                getConfig().getLong("world-change-grace-ms", 1200L)
        );

        specialBlockRadius = Math.max(
                0.5D,
                getConfig().getDouble("special-block-radius", 1.75D)
        );

        maxRestoreDistance = Math.max(
                0.0D,
                getConfig().getDouble("max-restore-distance", 1.50D)
        );

        String mode = getConfig().getString("correction-mode", "same-location");
        correctionMode = CorrectionMode.from(mode);

        ignoreDead = getConfig().getBoolean("ignore-dead", true);
        ignoreSpectator = getConfig().getBoolean("ignore-spectator", true);
        ignoreVehicles = getConfig().getBoolean("ignore-vehicles", true);

        specialBlocks.clear();

        Collection<String> configured = getConfig().getStringList("special-blocks");
        for (String name : configured) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                specialBlocks.add(material);
            } else {
                getLogger().warning("Unknown special block in config: " + name);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = now();

        State state = new State();
        state.joinAt = now;
        state.lastAcceptedAt = now;
        state.lastActivityAt = now;
        state.lastAcceptedLocation = player.getLocation().clone();

        states.put(player.getUniqueId(), state);

        debug(player, "JOIN bedrock=" + isBedrock(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /**
     * PlayerMoveEvent only represents movement that reached Paper's Bukkit
     * event layer. We intentionally treat it as an accepted/server-observable
     * movement signal and never attempt to infer missing packets.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        if (!isTracked(player)) return;

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        State state = states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new State()
        );

        long now = now();

        if (state.lastAcceptedLocation != null
                && state.lastAcceptedLocation.getWorld() != null
                && state.lastAcceptedLocation.getWorld().equals(to.getWorld())) {

            double delta = state.lastAcceptedLocation.distance(to);

            if (delta > 0.0001D) {
                state.lastAcceptedLocation = to.clone();
                state.lastAcceptedAt = now;
                state.lastMovementDelta = delta;
            }
        } else {
            state.lastAcceptedLocation = to.clone();
            state.lastAcceptedAt = now;
            state.lastMovementDelta = 0.0D;
        }

        state.lastMovementAt = now;

        debug(
                player,
                "MOVE delta=" + format(state.lastMovementDelta)
                        + " special=" + isNearSpecialBlock(player)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isTracked(player)) return;

        long now = now();

        State state = states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new State()
        );

        state.lastTeleportAt = now;
        state.lastActivityAt = now;
        state.failures.clear();

        Location to = event.getTo();
        if (to != null) {
            state.lastAcceptedLocation = to.clone();
            state.lastAcceptedAt = now;
        }

        debug(player, "TELEPORT cause=" + event.getCause());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!isTracked(player)) return;

        long now = now();

        State state = states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new State()
        );

        state.lastWorldChangeAt = now;
        state.lastActivityAt = now;
        state.failures.clear();
        state.lastAcceptedLocation = player.getLocation().clone();
        state.lastAcceptedAt = now;

        debug(player, "WORLD_CHANGE world=" + player.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isTracked(player)) return;

        State state = states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new State()
        );

        state.lastActivityAt = now();
        state.interactions++;

        debug(
                player,
                "INTERACT action=" + event.getAction()
                        + " block=" + (
                        event.getClickedBlock() == null
                                ? "none"
                                : event.getClickedBlock().getType()
                )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!isTracked(player)) return;

        State state = states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new State()
        );

        state.lastActivityAt = now();
        state.animations++;

        debug(player, "ANIMATION type=" + event.getAnimationType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent event) {
        if (!enabled || !handleFailMove) return;

        Player player = event.getPlayer();
        if (!isTracked(player)) return;

        State state = states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new State()
        );

        long now = now();

        state.lastFailAt = now;
        state.failures.addLast(now);
        trimFailures(state, now);

        debug(
                player,
                "FAIL_MOVE reason=" + event.getFailReason()
                        + " recentFailures=" + state.failures.size()
        );
    }

    /**
     * Watchdog logic:
     *
     * We cannot see a Bedrock packet that was lost before reaching Paper.
     * Therefore "stall" means:
     *   - player is Bedrock,
     *   - there was recent interaction/animation activity,
     *   - Paper has observed no movement for stallMs,
     *   - player is close to a configured special block,
     *   - and all grace/cooldown checks pass.
     *
     * We send ONE authoritative Bukkit teleport/sync pulse. We never loop
     * teleports and never change PlayerFailMoveEvent#allowed.
     */
    private void watchdogTick() {
        if (!enabled) return;

        long now = now();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isTracked(player)) continue;

            State state = states.get(player.getUniqueId());
            if (state == null) continue;

            if (!isEligibleForWatchdog(player, state, now)) continue;

            boolean nearSpecial = isNearSpecialBlock(player);
            boolean movementStalled =
                    now - state.lastMovementAt >= stallMs;

            boolean recentActivity =
                    now - state.lastActivityAt <= activityWindowMs;

            if (!nearSpecial || !movementStalled || !recentActivity) {
                continue;
            }

            // Do not repeatedly trigger while the same activity burst remains.
            if (now - state.lastCorrectionAt < correctionCooldownMs) {
                continue;
            }

            // A FailMove shortly before the stall strengthens the signal.
            boolean recentFail =
                    now - state.lastFailAt <= activityWindowMs;

            debug(
                    player,
                    "STALL detected"
                            + " movementAge=" + (now - state.lastMovementAt) + "ms"
                            + " activityAge=" + (now - state.lastActivityAt) + "ms"
                            + " failAge=" + (now - state.lastFailAt) + "ms"
                            + " nearSpecial=" + nearSpecial
                            + " recentFail=" + recentFail
            );

            applyOneCorrection(player, state, now, recentFail);
        }
    }

    private void applyOneCorrection(
            Player player,
            State state,
            long now,
            boolean recentFail
    ) {
        Location current = player.getLocation();

        if (current.getWorld() == null) return;

        Location target;

        if (correctionMode == CorrectionMode.LAST_ACCEPTED
                && state.lastAcceptedLocation != null
                && state.lastAcceptedLocation.getWorld() != null
                && state.lastAcceptedLocation.getWorld().equals(current.getWorld())
                && current.distance(state.lastAcceptedLocation) <= maxRestoreDistance) {

            target = state.lastAcceptedLocation.clone();
            target.setYaw(current.getYaw());
            target.setPitch(current.getPitch());
        } else {
            // "same-location" is deliberately a synchronization pulse:
            // same XYZ, current rotation. It avoids inventing a movement
            // destination and avoids repeated teleporting.
            target = current.clone();
        }

        state.lastCorrectionAt = now;
        state.lastActivityAt = 0L;
        state.lastMovementAt = now;
        state.failures.clear();

        /*
         * One scheduled Bukkit teleport is used as the correction/sync pulse.
         * It is NOT a per-tick teleport and it does not run from inside
         * PlayerFailMoveEvent.
         */
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            if (!isTracked(player)) return;

            long currentTime = now();

            if (currentTime - state.lastCorrectionAt > 1000L) {
                // Defensive guard against delayed scheduler execution.
                return;
            }

            player.teleport(
                    target,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            state.lastTeleportAt = currentTime;
            state.lastAcceptedLocation = target.clone();
            state.lastAcceptedAt = currentTime;
            state.lastMovementAt = currentTime;

            debug(
                    player,
                    "CORRECTION sent mode=" + correctionMode
                            + " recentFail=" + recentFail
                            + " target=" + formatLocation(target)
            );
        });
    }

    private boolean isEligibleForWatchdog(
            Player player,
            State state,
            long now
    ) {
        if (isBypassed(player)) return false;

        if (ignoreDead && player.isDead()) return false;

        if (ignoreSpectator
                && player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        if (ignoreVehicles && player.isInsideVehicle()) return false;

        if (now - state.joinAt < joinGraceMs) return false;
        if (now - state.lastTeleportAt < teleportGraceMs) return false;
        if (now - state.lastWorldChangeAt < worldChangeGraceMs) return false;

        return true;
    }

    private boolean isTracked(Player player) {
        if (!player.isOnline()) return false;
        if (bedrockOnly && !isBedrock(player)) return false;
        return true;
    }

    private boolean isBypassed(Player player) {
        return bypassPermission != null
                && !bypassPermission.isBlank()
                && player.hasPermission(bypassPermission);
    }

    private boolean isNearSpecialBlock(Player player) {
        Location location = player.getLocation();

        int minX = location.getBlockX() - 2;
        int maxX = location.getBlockX() + 2;
        int minY = location.getBlockY() - 2;
        int maxY = location.getBlockY() + 2;
        int minZ = location.getBlockZ() - 2;
        int maxZ = location.getBlockZ() + 2;

        double radiusSquared = specialBlockRadius * specialBlockRadius;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = location.getWorld().getBlockAt(x, y, z);

                    if (!specialBlocks.contains(block.getType())) {
                        continue;
                    }

                    double dx = location.getX() - (x + 0.5D);
                    double dy = location.getY() - (y + 0.5D);
                    double dz = location.getZ() - (z + 0.5D);

                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void trimFailures(State state, long now) {
        while (!state.failures.isEmpty()
                && now - state.failures.peekFirst() > activityWindowMs) {
            state.failures.removeFirst();
        }
    }

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

    private void debug(Player player, String message) {
        if (!debug) return;

        getLogger().info(
                "[DEBUG] " + player.getName() + ": " + message
        );
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private String formatLocation(Location location) {
        return String.format(
                java.util.Locale.ROOT,
                "%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f",
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private enum CorrectionMode {
        SAME_LOCATION,
        LAST_ACCEPTED;

        static CorrectionMode from(String value) {
            if (value == null) return SAME_LOCATION;

            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "last-accepted" -> LAST_ACCEPTED;
                default -> SAME_LOCATION;
            };
        }
    }

    private static final class State {
        long joinAt;
        long lastMovementAt;
        long lastAcceptedAt;
        long lastActivityAt;
        long lastTeleportAt;
        long lastWorldChangeAt;
        long lastFailAt;
        long lastCorrectionAt;

        double lastMovementDelta;

        long interactions;
        long animations;

        Location lastAcceptedLocation;

        final Deque<Long> failures = new ArrayDeque<>();
    }
}
