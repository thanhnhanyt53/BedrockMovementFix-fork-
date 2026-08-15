package com.votri.bedrockmovementfix;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.block.data.BlockData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockMovementFixPlugin
        extends JavaPlugin
        implements Listener {

    private final Map<UUID, State> states =
            new ConcurrentHashMap<>();

    private final EnumSet<Material> specialBlocks =
            EnumSet.noneOf(Material.class);

    private BukkitTask watchdog;

    private boolean enabled;
    private boolean debug;
    private boolean bedrockOnly;
    private boolean ignoreDead;
    private boolean ignoreSpectator;
    private boolean ignoreVehicles;

    private String bypassPermission;

    private long intervalTicks;
    private long failWindowMs;
    private long confirmationWindowMs;
    private long progressWindowMs;
    private long correlationWindowMs;
    private long activityWindowMs;

    private long correctionCooldownMs;
    private long joinGraceMs;
    private long teleportGraceMs;
    private long worldChangeGraceMs;

    private int hardStallRequiredSamples;
    private int stallingRequiredSamples;
    private int recoveryRequiredSamples;

    private double minimumProgressDistance;
    private double hardStallZeroDistance;
    private double directionDotThreshold;

    private double specialRadius;
    private double maxRestoreDistance;

    private CorrectionMode correctionMode;

    private MovementDiagnosticLogger diagnosticLogger;

    @Override
    public void onEnable() {

        saveDefaultConfig();
        loadConfig();

        boolean logEnabled =
                getConfig().getBoolean(
                        "log-file-enabled",
                        true
                );

        String logFile =
                getConfig().getString(
                        "log-file-name",
                        "bedrock-movement-fix.log"
                );

        long maxBytes =
                getConfig().getLong(
                        "log-file-max-bytes",
                        2_097_152L
                );

        diagnosticLogger =
                new MovementDiagnosticLogger(
                        this,
                        logEnabled,
                        logFile,
                        maxBytes
                );

        logDiagnostic(
                "SYSTEM",
                "PLUGIN_ENABLED version=2.1.8 "
                        + "movement-bypass=false "
                        + "setAllowed=false "
                        + "special-safe-pass=REMOVED"
        );

        Bukkit.getPluginManager()
                .registerEvents(this, this);

        watchdog =
                Bukkit.getScheduler().runTaskTimer(
                        this,
                        this::watchdogTick,
                        intervalTicks,
                        intervalTicks
                );

        getLogger().info(
                "BedrockMovementFix 2.1.8 enabled."
        );

        getLogger().info(
                "Movement bypass disabled."
        );

        getLogger().info(
                "CLIPPED/STALLED correlation enabled."
        );
    }

    @Override
    public void onDisable() {

        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }

        if (diagnosticLogger != null) {

            logDiagnostic(
                    "SYSTEM",
                    "PLUGIN_DISABLED"
            );

            diagnosticLogger.close();
            diagnosticLogger = null;
        }

        states.clear();
    }

    private void loadConfig() {

        reloadConfig();

        enabled =
                getConfig().getBoolean(
                        "enabled",
                        true
                );

        debug =
                getConfig().getBoolean(
                        "debug",
                        true
                );

        bedrockOnly =
                getConfig().getBoolean(
                        "bedrock-only",
                        true
                );

        ignoreDead =
                getConfig().getBoolean(
                        "ignore-dead",
                        true
                );

        ignoreSpectator =
                getConfig().getBoolean(
                        "ignore-spectator",
                        true
                );

        ignoreVehicles =
                getConfig().getBoolean(
                        "ignore-vehicles",
                        true
                );

        bypassPermission =
                getConfig().getString(
                        "bypass-permission",
                        "bedrockmovementfix.bypass"
                );

        intervalTicks =
                Math.max(
                        1L,
                        getConfig().getLong(
                                "watchdog-interval-ticks",
                                2L
                        )
                );

        failWindowMs =
                Math.max(
                        100L,
                        getConfig().getLong(
                                "fail-move-window-ms",
                                1000L
                        )
                );

        confirmationWindowMs =
                Math.max(
                        100L,
                        getConfig().getLong(
                                "confirmation-window-ms",
                                500L
                        )
                );

        progressWindowMs =
                Math.max(
                        100L,
                        getConfig().getLong(
                                "progress-sample-window-ms",
                                1500L
                        )
                );

        correlationWindowMs =
                Math.max(
                        500L,
                        getConfig().getLong(
                                "correlation-window-ms",
                                1500L
                        )
                );

        activityWindowMs =
                Math.max(
                        100L,
                        getConfig().getLong(
                                "activity-window-ms",
                                1200L
                        )
                );

        correctionCooldownMs =
                Math.max(
                        500L,
                        getConfig().getLong(
                                "correction-cooldown-ms",
                                3000L
                        )
                );

        joinGraceMs =
                Math.max(
                        0L,
                        getConfig().getLong(
                                "join-grace-ms",
                                2500L
                        )
                );

        teleportGraceMs =
                Math.max(
                        0L,
                        getConfig().getLong(
                                "teleport-grace-ms",
                                1200L
                        )
                );

        worldChangeGraceMs =
                Math.max(
                        0L,
                        getConfig().getLong(
                                "world-change-grace-ms",
                                1200L
                        )
                );

        minimumProgressDistance =
                Math.max(
                        0.001D,
                        getConfig().getDouble(
                                "minimum-progress-distance",
                                0.08D
                        )
                );

        hardStallRequiredSamples =
                Math.max(
                        2,
                        getConfig().getInt(
                                "hard-stall-required-samples",
                                4
                        )
                );

        stallingRequiredSamples =
                Math.max(
                        2,
                        getConfig().getInt(
                                "stalling-required-samples",
                                2
                        )
                );

        recoveryRequiredSamples =
                Math.max(
                        1,
                        getConfig().getInt(
                                "recovery-required-samples",
                                2
                        )
                );

        hardStallZeroDistance =
                Math.max(
                        0.0001D,
                        getConfig().getDouble(
                                "hard-stall-zero-distance",
                                0.005D
                        )
                );

        directionDotThreshold =
                Math.max(
                        -1.0D,
                        Math.min(
                                1.0D,
                                getConfig().getDouble(
                                        "direction-dot-threshold",
                                        0.25D
                                )
                        )
                );

        specialRadius =
                Math.max(
                        0.5D,
                        getConfig().getDouble(
                                "special-block-radius",
                                1.75D
                        )
                );

        maxRestoreDistance =
                Math.max(
                        0.0D,
                        getConfig().getDouble(
                                "max-restore-distance",
                                1.5D
                        )
                );

        correctionMode =
                CorrectionMode.from(
                        getConfig().getString(
                                "correction-mode",
                                "last-progress"
                        )
                );

        specialBlocks.clear();

        for (String name :
                getConfig().getStringList(
                        "special-blocks"
                )) {

            Material material =
                    Material.matchMaterial(name);

            if (material != null) {

                specialBlocks.add(material);

            } else {

                getLogger().warning(
                        "Unknown special block: "
                                + name
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {

        Player player = event.getPlayer();

        long now = now();

        State state = new State();

        state.joinAt = now;
        state.lastMoveAt = now;
        state.lastActivityAt = now;

        Location location =
                player.getLocation().clone();

        state.lastAccepted =
                location.clone();

        state.lastProgressPosition =
                location.clone();

        state.lastAcceptedAt = now;
        state.lastProgressAt = now;
        state.lastMeaningfulProgressAt = now;

        states.put(
                player.getUniqueId(),
                state
        );

        logDiagnostic(
                player,
                "JOIN bedrock="
                        + isBedrock(player)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {

        Player player =
                event.getPlayer();

        State state =
                states.remove(
                        player.getUniqueId()
                );

        if (state != null) {

            logDiagnostic(
                    player,
                    "QUIT phase="
                            + state.phase
            );
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onMove(PlayerMoveEvent event) {

        if (!enabled) {
            return;
        }

        Player player =
                event.getPlayer();

        if (!tracked(player)) {
            return;
        }

        Location to =
                event.getTo();

        if (to == null
                || to.getWorld() == null) {
            return;
        }

        State state =
                state(player);

        long now = now();

        Location previous =
                state.lastAccepted;

        double delta = 0.0D;

        Vector direction = null;

        if (previous != null
                && previous.getWorld() != null
                && previous.getWorld()
                .equals(to.getWorld())) {

            Vector displacement =
                    to.toVector()
                            .subtract(
                                    previous.toVector()
                            );

            delta =
                    displacement.length();

            if (delta > 0.00001D) {

                direction =
                        displacement.normalize();
            }
        }

        if (direction != null) {

            state.lastDirection =
                    direction.clone();

            state.lastDirectionAt =
                    now;
        }

        state.lastAccepted =
                to.clone();

        state.lastAcceptedAt =
                now;

        state.lastMoveAt =
                now;

        state.lastActivityAt =
                now;

        state.progressSamples.addLast(
                new MovementSample(
                        now,
                        delta,
                        state.lastDirection
                )
        );

        trimProgressSamples(
                state,
                now
        );

        if (delta >= minimumProgressDistance) {

            state.lastMeaningfulProgressAt =
                    now;

            state.lastProgressAt =
                    now;

            state.lastProgressPosition =
                    to.clone();
        }

        boolean special =
                nearSpecial(player);

        logDiagnostic(
                player,
                "MOVE delta="
                        + fmt(delta)
                        + " special="
                        + special
                        + " phase="
                        + state.phase
        );

        debug(
                player,
                "MOVE delta="
                        + fmt(delta)
                        + " special="
                        + special
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(
            PlayerTeleportEvent event
    ) {

        Player player =
                event.getPlayer();

        if (!tracked(player)) {
            return;
        }

        State state =
                state(player);

        long now = now();

        state.lastTeleportAt = now;
        state.lastActivityAt = now;
        state.lastMoveAt = now;

        state.lastMeaningfulProgressAt =
                now;

        state.lastProgressAt =
                now;

        state.failures.clear();
        state.progressSamples.clear();

        state.phase =
                Phase.NORMAL;

        state.hardStallSince = 0L;
        state.consecutiveHardStallSamples = 0;
        state.consecutiveStallingSamples = 0;
        state.recoverySamples = 0;

        if (event.getTo() != null) {

            Location location =
                    event.getTo().clone();

            state.lastAccepted =
                    location.clone();

            state.lastProgressPosition =
                    location.clone();

            state.lastAcceptedAt =
                    now;
        }

        logDiagnostic(
                player,
                "TELEPORT cause="
                        + event.getCause()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(
            PlayerChangedWorldEvent event
    ) {

        Player player =
                event.getPlayer();

        if (!tracked(player)) {
            return;
        }

        State state =
                state(player);

        long now = now();

        state.lastWorldChangeAt =
                now;

        state.lastActivityAt =
                now;

        state.lastMoveAt =
                now;

        state.lastMeaningfulProgressAt =
                now;

        state.failures.clear();
        state.progressSamples.clear();

        state.phase =
                Phase.NORMAL;

        state.hardStallSince = 0L;
        state.consecutiveHardStallSamples = 0;
        state.consecutiveStallingSamples = 0;

        Location location =
                player.getLocation().clone();

        state.lastAccepted =
                location.clone();

        state.lastProgressPosition =
                location.clone();

        state.lastAcceptedAt =
                now;

        logDiagnostic(
                player,
                "WORLD_CHANGE world="
                        + player.getWorld()
                        .getName()
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onInteract(
            PlayerInteractEvent event
    ) {

        Player player =
                event.getPlayer();

        if (!tracked(player)) {
            return;
        }

        State state =
                state(player);

        state.lastActivityAt =
                now();

        state.interactions++;

        String block =
                event.getClickedBlock() == null
                        ? "none"
                        : event.getClickedBlock()
                        .getType()
                        .name();

        logDiagnostic(
                player,
                "INTERACT action="
                        + event.getAction()
                        + " block="
                        + block
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnimation(
            PlayerAnimationEvent event
    ) {

        Player player =
                event.getPlayer();

        if (!tracked(player)) {
            return;
        }

        State state =
                state(player);

        state.lastActivityAt =
                now();

        state.animations++;

        logDiagnostic(
                player,
                "ANIMATION type="
                        + event.getAnimationType()
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onFailMove(
            PlayerFailMoveEvent event
    ) {

        if (!enabled) {
            return;
        }

        Player player =
                event.getPlayer();

        if (!tracked(player)) {
            return;
        }

        State state =
                state(player);

        long now = now();

        String reason =
                event.getFailReason().name();

        state.lastFailAt =
                now;

        state.lastActivityAt =
                now;

        if ("CLIPPED_INTO_BLOCK".equals(reason)) {

            state.lastClippedAt =
                    now;
        }

        state.failures.addLast(
                new Failure(
                        now,
                        reason
                )
        );

        trimFailures(
                state,
                now
        );

        long clipped =
                recentClipped(
                        state,
                        now
                );

        logDiagnostic(
                player,
                "FAIL_MOVE reason="
                        + reason
                        + " clippedRecent="
                        + clipped
                        + " phase="
                        + state.phase
        );

        debug(
                player,
                "FAIL_MOVE reason="
                        + reason
                        + " clippedRecent="
                        + clipped
        );

        /*
         * INTENTIONALLY NO:
         *
         * event.setAllowed(true);
         *
         * The failed movement remains rejected by Paper.
         */
    }

    private void watchdogTick() {

        if (!enabled) {
            return;
        }

        long now = now();

        for (Player player :
                Bukkit.getOnlinePlayers()) {

            if (!tracked(player)) {
                continue;
            }

            State state =
                    states.get(
                            player.getUniqueId()
                    );

            if (state == null) {
                continue;
            }

            if (!eligible(
                    player,
                    state,
                    now
            )) {
                continue;
            }

            if (!nearSpecial(player)) {

                if (state.phase != Phase.NORMAL) {

                    logState(
                            player,
                            state,
                            state.phase
                                    + " -> NORMAL"
                                    + " reason=left-special-area"
                    );
                }

                resetDetection(state);

                continue;
            }

            trimFailures(
                    state,
                    now
            );

            trimProgressSamples(
                    state,
                    now
            );

            long clipped =
                    recentClipped(
                            state,
                            now
                    );

            boolean clippedSignal =
                    state.lastClippedAt > 0
                            && now
                            - state.lastClippedAt
                            <= correlationWindowMs;

            boolean hardStall =
                    updateHardStall(
                            state,
                            now
                    );

            boolean directionHeld =
                    hasHeldDirection(
                            state,
                            now
                    );

            boolean tryingToMove =
                    playerStillTryingToMove(
                            state,
                            now
                    );

            boolean recovery =
                    updateRecovery(
                            state,
                            now
                    );

            boolean active =
                    now
                            - state.lastActivityAt
                            <= activityWindowMs;

            boolean correlation =
                    correlated(
                            state,
                            now
                    );

            boolean anySignal =
                    clippedSignal
                            || hardStall;

            switch (state.phase) {

                case NORMAL -> {

                    if (anySignal && active) {

                        state.phase =
                                Phase.SUSPECTED;

                        state.suspectedAt =
                                now;

                        logState(
                                player,
                                state,
                                "NORMAL -> SUSPECTED"
                                        + " clipped="
                                        + clipped
                                        + " hardStall="
                                        + hardStall
                        );
                    }
                }

                case SUSPECTED -> {

                    if (recovery) {

                        logState(
                                player,
                                state,
                                "SUSPECTED -> NORMAL"
                                        + " reason=recovery"
                        );

                        resetDetection(state);

                        continue;
                    }

                    if (!active) {

                        resetDetection(state);

                        continue;
                    }

                    if (now
                            - state.suspectedAt
                            > correlationWindowMs) {

                        logState(
                                player,
                                state,
                                "SUSPECTED -> NORMAL"
                                        + " reason=window-expired"
                        );

                        resetDetection(state);

                        continue;
                    }

                    if (clippedSignal
                            && hardStall
                            && directionHeld
                            && tryingToMove
                            && correlation) {

                        state.phase =
                                Phase.STALLING;

                        state.stallingAt =
                                now;

                        state.consecutiveStallingSamples =
                                state.consecutiveHardStallSamples;

                        logState(
                                player,
                                state,
                                "SUSPECTED -> STALLING"
                                        + " clipped="
                                        + clipped
                                        + " hardSamples="
                                        + state.consecutiveHardStallSamples
                        );
                    }
                }

                case STALLING -> {

                    if (recovery) {

                        logState(
                                player,
                                state,
                                "STALLING -> NORMAL"
                                        + " reason=recovery"
                        );

                        resetDetection(state);

                        continue;
                    }

                    if (!active
                            || !clippedSignal
                            || !hardStall
                            || !directionHeld
                            || !tryingToMove
                            || !correlation) {

                        logState(
                                player,
                                state,
                                "STALLING -> NORMAL"
                                        + " reason=condition-lost"
                        );

                        resetDetection(state);

                        continue;
                    }

                    if (now
                            - state.stallingAt
                            > correlationWindowMs) {

                        resetDetection(state);

                        continue;
                    }

                    state.consecutiveStallingSamples++;

                    if (state.consecutiveStallingSamples
                            >= stallingRequiredSamples) {

                        state.phase =
                                Phase.CONFIRMED;

                        state.confirmedAt =
                                now;

                        logState(
                                player,
                                state,
                                "STALLING -> CONFIRMED"
                        );
                    }
                }

                case CONFIRMED -> {

                    if (recovery) {

                        logState(
                                player,
                                state,
                                "CONFIRMED -> NORMAL"
                                        + " reason=recovery"
                        );

                        resetDetection(state);

                        continue;
                    }

                    if (!active
                            || !clippedSignal
                            || !hardStall
                            || !directionHeld
                            || !tryingToMove
                            || !correlation) {

                        logState(
                                player,
                                state,
                                "CONFIRMED -> NORMAL"
                                        + " reason=condition-lost"
                        );

                        resetDetection(state);

                        continue;
                    }

                    if (now
                            - state.lastCorrectionAt
                            >= correctionCooldownMs) {

                        correctOnce(
                                player,
                                state,
                                now,
                                clipped
                        );
                    }
                }
            }
        }
    }

    private boolean updateHardStall(
            State state,
            long now
    ) {

        if (state.progressSamples.isEmpty()
                || state.lastDirection == null
                || now
                - state.lastDirectionAt
                > correlationWindowMs) {

            state.consecutiveHardStallSamples = 0;
            state.hardStallSince = 0L;

            return false;
        }

        int consecutive = 0;

        long since = 0L;

        MovementSample[] samples =
                state.progressSamples.toArray(
                        new MovementSample[0]
                );

        for (int i = samples.length - 1;
             i >= 0;
             i--) {

            MovementSample sample =
                    samples[i];

            if (now
                    - sample.time
                    > correlationWindowMs) {
                break;
            }

            if (sample.delta
                    <= hardStallZeroDistance) {

                consecutive++;

                since =
                        sample.time;

            } else {

                break;
            }
        }

        state.consecutiveHardStallSamples =
                consecutive;

        state.hardStallSince =
                consecutive > 0
                        ? since
                        : 0L;

        return consecutive
                >= hardStallRequiredSamples;
    }

    private boolean correlated(
            State state,
            long now
    ) {

        if (state.lastClippedAt <= 0
                || state.hardStallSince <= 0) {
            return false;
        }

        if (now
                - state.lastClippedAt
                > correlationWindowMs) {
            return false;
        }

        if (now
                - state.hardStallSince
                > correlationWindowMs) {
            return false;
        }

        long difference =
                Math.abs(
                        state.lastClippedAt
                                - state.hardStallSince
                );

        return difference
                <= correlationWindowMs;
    }

    private boolean hasHeldDirection(
            State state,
            long now
    ) {

        return state.lastDirection != null
                && now
                - state.lastDirectionAt
                <= correlationWindowMs;
    }

    private boolean playerStillTryingToMove(
            State state,
            long now
    ) {

        return state.lastActivityAt > 0
                && now
                - state.lastActivityAt
                <= Math.min(
                        activityWindowMs,
                        correlationWindowMs
                )
                && state.lastDirection != null
                && now
                - state.lastDirectionAt
                <= correlationWindowMs;
    }

    private boolean updateRecovery(
            State state,
            long now
    ) {

        int count = 0;

        for (MovementSample sample :
                state.progressSamples) {

            if (now
                    - sample.time
                    > confirmationWindowMs) {
                continue;
            }

            if (sample.delta
                    >= minimumProgressDistance) {

                count++;
            }
        }

        state.recoverySamples =
                count;

        return count
                >= recoveryRequiredSamples;
    }

    private void resetDetection(
            State state
    ) {

        state.phase =
                Phase.NORMAL;

        state.suspectedAt = 0L;
        state.stallingAt = 0L;
        state.confirmedAt = 0L;

        state.hardStallSince = 0L;

        state.consecutiveHardStallSamples = 0;
        state.consecutiveStallingSamples = 0;
        state.recoverySamples = 0;
    }

    /**
     * Correction is NOT a movement bypass.
     *
     * It NEVER teleports to the rejected destination.
     *
     * It only restores the player to the last meaningful
     * position that the server had already accepted.
     */
    private void correctOnce(
            Player player,
            State state,
            long now,
            long clipped
    ) {

        Location current =
                player.getLocation();

        Location target =
                state.lastProgressPosition;

        if (target == null
                || target.getWorld() == null
                || current.getWorld() == null
                || !target.getWorld()
                .equals(current.getWorld())) {

            logDiagnostic(
                    player,
                    "CORRECTION_REJECTED"
                            + " reason=no-valid-anchor"
            );

            resetDetection(state);

            return;
        }

        if (current.distance(target)
                > maxRestoreDistance) {

            logDiagnostic(
                    player,
                    "CORRECTION_REJECTED"
                            + " reason=anchor-too-far"
                            + " distance="
                            + fmt(
                            current.distance(target)
                    )
            );

            resetDetection(state);

            return;
        }

        Location correctionTarget =
                target.clone();

        correctionTarget.setYaw(
                current.getYaw()
        );

        correctionTarget.setPitch(
                current.getPitch()
        );

        /*
         * Critical safety check:
         *
         * The correction target itself must be physically
         * valid. We never teleport the player into a block.
         */
        if (!isSafePlayerPosition(
                player,
                correctionTarget
        )) {

            logDiagnostic(
                    player,
                    "CORRECTION_REJECTED"
                            + " reason=target-collision"
                            + " target="
                            + loc(correctionTarget)
            );

            resetDetection(state);

            return;
        }

        final long correctionId =
                ++state.correctionId;

        state.lastCorrectionAt =
                now;

        /*
         * Consume the confirmation immediately.
         *
         * This prevents repeated corrections while the
         * scheduled teleport is pending.
         */
        state.phase =
                Phase.NORMAL;

        logState(
                player,
                state,
                "CONFIRMED -> NORMAL"
                        + " reason=correction-armed"
                        + " id="
                        + correctionId
        );

        logDiagnostic(
                player,
                "CORRECTION_ARMED"
                        + " id="
                        + correctionId
                        + " clipped="
                        + clipped
                        + " target="
                        + loc(correctionTarget)
        );

        Bukkit.getScheduler().runTask(
                this,
                () -> {

                    if (!player.isOnline()
                            || !tracked(player)) {

                        logDiagnostic(
                                player,
                                "CORRECTION_CANCELLED"
                                        + " id="
                                        + correctionId
                                        + " reason=offline"
                        );

                        return;
                    }

                    /*
                     * Revalidate immediately before teleport.
                     *
                     * The world may have changed between
                     * detection and execution.
                     */
                    if (!isSafePlayerPosition(
                            player,
                            correctionTarget
                    )) {

                        logDiagnostic(
                                player,
                                "CORRECTION_CANCELLED"
                                        + " id="
                                        + correctionId
                                        + " reason=target-no-longer-safe"
                        );

                        return;
                    }

                    /*
                     * EXACTLY ONE teleport.
                     *
                     * Never use event.setAllowed(true).
                     * Never teleport to event.getTo().
                     */
                    boolean success =
                            player.teleport(
                                    correctionTarget,
                                    PlayerTeleportEvent.TeleportCause.PLUGIN
                            );

                    if (!success) {

                        logDiagnostic(
                                player,
                                "CORRECTION_FAILED"
                                        + " id="
                                        + correctionId
                        );

                        return;
                    }

                    long time =
                            now();

                    state.lastTeleportAt =
                            time;

                    state.lastAccepted =
                            correctionTarget.clone();

                    state.lastProgressPosition =
                            correctionTarget.clone();

                    state.lastAcceptedAt =
                            time;

                    state.lastMoveAt =
                            time;

                    state.lastMeaningfulProgressAt =
                            time;

                    state.lastProgressAt =
                            time;

                    state.lastActivityAt =
                            time;

                    state.failures.clear();
                    state.progressSamples.clear();

                    state.hardStallSince =
                            0L;

                    state.consecutiveHardStallSamples =
                            0;

                    state.consecutiveStallingSamples =
                            0;

                    state.recoverySamples =
                            0;

                    logDiagnostic(
                            player,
                            "CORRECTION_SENT"
                                    + " id="
                                    + correctionId
                                    + " target="
                                    + loc(correctionTarget)
                    );
                }
        );
    }

    /**
     * Full player AABB validation.
     *
     * This does not make special blocks passable.
     *
     * A target is safe only if the player's bounding box
     * does not overlap any collision shape at the target.
     */
    private boolean isSafePlayerPosition(
            Player player,
            Location target
    ) {

        if (target == null
                || target.getWorld() == null) {
            return false;
        }

        World world =
                target.getWorld();

        BoundingBox currentBox =
                player.getBoundingBox();

        Location current =
                player.getLocation();

        double dx =
                target.getX()
                        - current.getX();

        double dy =
                target.getY()
                        - current.getY();

        double dz =
                target.getZ()
                        - current.getZ();

        BoundingBox targetBox =
                currentBox.shift(
                        dx,
                        dy,
                        dz
                );

        int minX =
                floor(targetBox.getMinX());

        int maxX =
                floor(
                        targetBox.getMaxX()
                );

        int minY =
                floor(targetBox.getMinY());

        int maxY =
                floor(
                        targetBox.getMaxY()
                );

        int minZ =
                floor(targetBox.getMinZ());

        int maxZ =
                floor(targetBox.getMaxZ());

        /*
         * Include one block of safety around the AABB.
         */
        minX--;
        maxX++;

        minY--;
        maxY++;

        minZ--;
        maxZ++;

        for (int x = minX;
             x <= maxX;
             x++) {

            for (int y = minY;
                 y <= maxY;
                 y++) {

                for (int z = minZ;
                     z <= maxZ;
                     z++) {

                    Block block =
                            world.getBlockAt(
                                    x,
                                    y,
                                    z
                            );

                    if (block.getType()
                            == Material.AIR) {
                        continue;
                    }

                    /*
                     * Block state check.
                     */
                    BlockData data =
                            block.getBlockData();

                    if (data == null) {
                        continue;
                    }

                    /*
                     * If the block has no collision,
                     * it cannot physically trap the player.
                     */
                    if (block.isPassable()
                            && block.getCollisionShape()
                            .getBoundingBoxes()
                            .isEmpty()) {

                        continue;
                    }

                    /*
                     * Check actual collision shape rather
                     * than simply assuming a full cube.
                     */
                    for (BoundingBox shape :
                            block.getCollisionShape()
                                    .getBoundingBoxes()) {

                        BoundingBox worldShape =
                                shape.shift(
                                        x,
                                        y,
                                        z
                                );

                        if (targetBox.overlaps(
                                worldShape
                        )) {

                            logDiagnostic(
                                    player,
                                    "AABB_BLOCK_COLLISION"
                                            + " block="
                                            + block.getType()
                                            + " xyz="
                                            + x + ","
                                            + y + ","
                                            + z
                            );

                            return false;
                        }
                    }
                }
            }
        }

        /*
         * Additional feet/head sanity checks.
         */
        if (!isPassableAt(
                world,
                target.getX(),
                target.getY(),
                target.getZ()
        )) {

            return false;
        }

        if (!isPassableAt(
                world,
                target.getX(),
                target.getY()
                        + player.getHeight()
                        - 0.05D,
                target.getZ()
        )) {

            return false;
        }

        return true;
    }

    private boolean isPassableAt(
            World world,
            double x,
            double y,
            double z
    ) {

        Block block =
                world.getBlockAt(
                        floor(x),
                        floor(y),
                        floor(z)
                );

        /*
         * Passable is necessary but not sufficient;
         * also inspect the collision shape.
         */
        if (!block.isPassable()) {
            return false;
        }

        return block.getCollisionShape()
                .getBoundingBoxes()
                .isEmpty();
    }

    private boolean eligible(
            Player player,
            State state,
            long now
    ) {

        if (bypassPermission != null
                && !bypassPermission.isBlank()
                && player.hasPermission(
                        bypassPermission
                )) {

            return false;
        }

        if (ignoreDead
                && player.isDead()) {
            return false;
        }

        if (ignoreSpectator
                && player.getGameMode()
                == GameMode.SPECTATOR) {
            return false;
        }

        if (ignoreVehicles
                && player.isInsideVehicle()) {
            return false;
        }

        return now
                - state.joinAt
                >= joinGraceMs

                && now
                - state.lastTeleportAt
                >= teleportGraceMs

                && now
                - state.lastWorldChangeAt
                >= worldChangeGraceMs;
    }

    private boolean tracked(
            Player player
    ) {

        return player.isOnline()
                && (
                !bedrockOnly
                        || isBedrock(player)
        );
    }

    private State state(
            Player player
    ) {

        return states.computeIfAbsent(
                player.getUniqueId(),
                ignored -> {

                    State state =
                            new State();

                    long now = now();

                    state.joinAt =
                            now;

                    state.lastMoveAt =
                            now;

                    state.lastActivityAt =
                            now;

                    Location location =
                            player.getLocation()
                                    .clone();

                    state.lastAccepted =
                            location.clone();

                    state.lastProgressPosition =
                            location.clone();

                    state.lastAcceptedAt =
                            now;

                    state.lastProgressAt =
                            now;

                    state.lastMeaningfulProgressAt =
                            now;

                    return state;
                }
        );
    }

    private boolean nearSpecial(
            Player player
    ) {

        Location location =
                player.getLocation();

        double radiusSquared =
                specialRadius
                        * specialRadius;

        int centerX =
                location.getBlockX();

        int centerY =
                location.getBlockY();

        int centerZ =
                location.getBlockZ();

        int range = 2;

        for (int x =
             centerX - range;
             x <= centerX + range;
             x++) {

            for (int y =
                 centerY - range;
                 y <= centerY + range;
                 y++) {

                for (int z =
                     centerZ - range;
                     z <= centerZ + range;
                     z++) {

                    Block block =
                            location.getWorld()
                                    .getBlockAt(
                                            x,
                                            y,
                                            z
                                    );

                    if (!specialBlocks.contains(
                            block.getType()
                    )) {
                        continue;
                    }

                    double dx =
                            location.getX()
                                    - (x + 0.5D);

                    double dy =
                            location.getY()
                                    - (y + 0.5D);

                    double dz =
                            location.getZ()
                                    - (z + 0.5D);

                    if (dx * dx
                            + dy * dy
                            + dz * dz
                            <= radiusSquared) {

                        return true;
                    }
                }
            }
        }

        return false;
    }

    private long recentClipped(
            State state,
            long now
    ) {

        long count = 0;

        for (Failure failure :
                state.failures) {

            if (now
                    - failure.time
                    <= failWindowMs
                    && "CLIPPED_INTO_BLOCK"
                    .equals(
                            failure.reason
                    )) {

                count++;
            }
        }

        return count;
    }

    private void trimFailures(
            State state,
            long now
    ) {

        while (!state.failures.isEmpty()
                && now
                - state.failures.peekFirst().time
                > failWindowMs) {

            state.failures.removeFirst();
        }
    }

    private void trimProgressSamples(
            State state,
            long now
    ) {

        while (!state.progressSamples.isEmpty()
                && now
                - state.progressSamples.peekFirst().time
                > progressWindowMs) {

            state.progressSamples.removeFirst();
        }
    }

    private boolean isBedrock(
            Player player
    ) {

        try {

            Class<?> apiClass =
                    Class.forName(
                            "org.geysermc.floodgate.api.FloodgateApi"
                    );

            Object api =
                    apiClass
                            .getMethod(
                                    "getInstance"
                            )
                            .invoke(null);

            Object result =
                    apiClass
                            .getMethod(
                                    "isFloodgatePlayer",
                                    UUID.class
                            )
                            .invoke(
                                    api,
                                    player.getUniqueId()
                            );

            return result instanceof Boolean
                    && (Boolean) result;

        } catch (
                ReflectiveOperationException
                        | LinkageError ignored
        ) {

            return false;
        }
    }

    private void debug(
            Player player,
            String message
    ) {

        if (!debug) {
            return;
        }

        getLogger().info(
                "[DEBUG] "
                        + player.getName()
                        + ": "
                        + message
        );

        logDiagnostic(
                player,
                "DEBUG " + message
        );
    }

    private void logState(
            Player player,
            State state,
            String message
    ) {

        logDiagnostic(
                player,
                "STATE " + message
        );
    }

    private void logDiagnostic(
            Player player,
            String message
    ) {

        if (diagnosticLogger == null) {
            return;
        }

        diagnosticLogger.log(
                player.getName(),
                message
        );
    }

    private void logDiagnostic(
            String player,
            String message
    ) {

        if (diagnosticLogger == null) {
            return;
        }

        diagnosticLogger.log(
                player,
                message
        );
    }

    private static int floor(
            double value
    ) {

        return (int) Math.floor(value);
    }

    private static String fmt(
            double value
    ) {

        return String.format(
                Locale.ROOT,
                "%.3f",
                value
        );
    }

    private static String loc(
            Location location
    ) {

        return String.format(
                Locale.ROOT,
                "%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f",
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    private static long now() {

        return System.currentTimeMillis();
    }

    private enum Phase {

        NORMAL,
        SUSPECTED,
        STALLING,
        CONFIRMED
    }

    private enum CorrectionMode {

        SAME_LOCATION,
        LAST_PROGRESS;

        static CorrectionMode from(
                String value
        ) {

            if ("last-progress"
                    .equalsIgnoreCase(value)) {

                return LAST_PROGRESS;
            }

            return SAME_LOCATION;
        }
    }

    private static final class Failure {

        final long time;
        final String reason;

        Failure(
                long time,
                String reason
        ) {

            this.time = time;
            this.reason = reason;
        }
    }

    private static final class MovementSample {

        final long time;
        final double delta;
        final Vector direction;

        MovementSample(
                long time,
                double delta,
                Vector direction
        ) {

            this.time = time;
            this.delta = delta;

            this.direction =
                    direction == null
                            ? null
                            : direction.clone();
        }
    }

    private static final class State {

        long joinAt;
        long lastMoveAt;

        long lastAcceptedAt;
        long lastMeaningfulProgressAt;
        long lastProgressAt;

        long lastActivityAt;
        long lastTeleportAt;
        long lastWorldChangeAt;
        long lastFailAt;
        long lastCorrectionAt;

        long lastDirectionAt;

        long suspectedAt;
        long stallingAt;
        long confirmedAt;

        long lastClippedAt;
        long hardStallSince;

        int consecutiveHardStallSamples;
        int consecutiveStallingSamples;
        int recoverySamples;

        long interactions;
        long animations;
        long correctionId;

        Location lastAccepted;
        Location lastProgressPosition;

        Vector lastDirection;

        Phase phase =
                Phase.NORMAL;

        final Deque<Failure> failures =
                new ArrayDeque<>();

        final Deque<MovementSample> progressSamples =
                new ArrayDeque<>();
    }
}