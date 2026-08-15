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
    private long intervalTicks, failThreshold, failWindowMs, desyncWindowMs;
    private long activityWindowMs, correctionCooldownMs, joinGraceMs, teleportGraceMs, worldChangeGraceMs;
    private int samplesRequired;
    private double lowDelta, specialRadius, maxRestoreDistance;
    private CorrectionMode correctionMode;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        watchdog = Bukkit.getScheduler().runTaskTimer(this, this::watchdogTick, intervalTicks, intervalTicks);
        getLogger().info("BedrockMovementFix 2.1.2 enabled.");
        getLogger().info("Mode: CLIPPED_INTO_BLOCK burst watchdog; no ProtocolLib/PacketEvents.");
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
        desyncWindowMs = Math.max(100, getConfig().getLong("movement-desync-window-ms", 700));
        activityWindowMs = Math.max(100, getConfig().getLong("activity-window-ms", 1200));
        correctionCooldownMs = Math.max(500, getConfig().getLong("correction-cooldown-ms", 3000));
        joinGraceMs = Math.max(0, getConfig().getLong("join-grace-ms", 2500));
        teleportGraceMs = Math.max(0, getConfig().getLong("teleport-grace-ms", 1200));
        worldChangeGraceMs = Math.max(0, getConfig().getLong("world-change-grace-ms", 1200));

        lowDelta = Math.max(0, getConfig().getDouble("movement-low-delta", 0.15));
        samplesRequired = Math.max(2, getConfig().getInt("movement-samples-required", 3));
        specialRadius = Math.max(.5, getConfig().getDouble("special-block-radius", 1.75));
        maxRestoreDistance = Math.max(0, getConfig().getDouble("max-restore-distance", 1.5));
        correctionMode = CorrectionMode.from(getConfig().getString("correction-mode", "same-location"));

        specialBlocks.clear();
        for (String s : getConfig().getStringList("special-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) specialBlocks.add(m);
            else getLogger().warning("Unknown special block: " + s);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); long n=now();
        State s=new State();
        s.join=n; s.lastMove=n; s.lastMeaningfulMove=n; s.lastActivity=n;
        s.lastAccepted=p.getLocation().clone();
        states.put(p.getUniqueId(), s);
        debug(p, "JOIN bedrock="+isBedrock(p));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;
        Player p=e.getPlayer();
        if (!tracked(p)) return;
        Location to=e.getTo();
        if (to==null || to.getWorld()==null) return;

        State s=state(p); long n=now();
        double delta = 0;
        if (s.lastAccepted != null && s.lastAccepted.getWorld()!=null
                && s.lastAccepted.getWorld().equals(to.getWorld())) {
            delta=s.lastAccepted.distance(to);
        }

        s.lastAccepted=to.clone();
        s.lastAcceptedAt=n;
        s.lastMove=n;
        s.lastDelta=delta;
        s.recentDeltas.addLast(new MovementSample(n, delta));
        trimDeltas(s,n);

        if (delta >= lowDelta) s.lastMeaningfulMove=n;
        s.lastActivity=n;

        debug(p, "MOVE delta="+fmt(delta)+" special="+nearSpecial(p));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p=e.getPlayer();
        if (!tracked(p)) return;
        State s=state(p); long n=now();
        s.lastTeleport=n; s.lastActivity=n; s.lastMove=n; s.lastMeaningfulMove=n;
        s.fails.clear(); s.recentDeltas.clear();
        if(e.getTo()!=null) { s.lastAccepted=e.getTo().clone(); s.lastAcceptedAt=n; }
        debug(p,"TELEPORT cause="+e.getCause());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p=e.getPlayer();
        if (!tracked(p)) return;
        State s=state(p); long n=now();
        s.lastWorld=n; s.lastActivity=n; s.lastMove=n; s.lastMeaningfulMove=n;
        s.fails.clear(); s.recentDeltas.clear(); s.lastAccepted=p.getLocation().clone();
        s.lastAcceptedAt=n;
        debug(p,"WORLD_CHANGE world="+p.getWorld().getName());
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent e) {
        Player p=e.getPlayer();
        if(!tracked(p)) return;
        State s=state(p); s.lastActivity=now(); s.interactions++;
        debug(p,"INTERACT action="+e.getAction()+" block="+
                (e.getClickedBlock()==null?"none":e.getClickedBlock().getType()));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onAnimation(PlayerAnimationEvent e) {
        Player p=e.getPlayer();
        if(!tracked(p)) return;
        State s=state(p); s.lastActivity=now(); s.animations++;
        debug(p,"ANIMATION type="+e.getAnimationType());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent e) {
        if(!enabled) return;
        Player p=e.getPlayer();
        if(!tracked(p)) return;

        State s=state(p); long n=now();
        String reason=e.getFailReason().name();
        s.lastFail=n;
        s.lastActivity=n;
        s.fails.addLast(new Failure(n, reason));
        trimFailures(s,n);

        debug(p,"FAIL_MOVE reason="+reason+" clippedRecent="+recentClipped(s,n));

        // Deliberately NEVER call e.setAllowed(true).
    }

    private void watchdogTick() {
        if(!enabled) return;
        long n=now();

        for(Player p:Bukkit.getOnlinePlayers()) {
            if(!tracked(p)) continue;
            State s=states.get(p.getUniqueId());
            if(s==null || !eligible(p,s,n)) continue;

            boolean special=nearSpecial(p);
            if(!special) continue;

            long clipped=recentClipped(s,n);
            boolean failureBurst=clipped>=failThreshold;
            boolean recentActivity=n-s.lastActivity<=activityWindowMs;
            boolean cooldownReady=n-s.lastCorrection>=correctionCooldownMs;
            boolean movementPattern=hasRestrictedMovement(s,n);
            boolean meaningfulProgressStalled=n-s.lastMeaningfulMove>=desyncWindowMs;

            debug(p,"WATCH clipped="+clipped
                    +" burst="+failureBurst
                    +" restricted="+movementPattern
                    +" progressStall="+meaningfulProgressStalled
                    +" active="+recentActivity);

            if(failureBurst && recentActivity && cooldownReady
                    && movementPattern && meaningfulProgressStalled) {
                correctOnce(p,s,n,clipped);
            }
        }
    }

    private boolean hasRestrictedMovement(State s,long n) {
        trimDeltas(s,n);
        if(s.recentDeltas.size()<samplesRequired) return false;

        int low=0;
        for(MovementSample sample:s.recentDeltas) {
            if(sample.delta<lowDelta) low++;
        }

        // We intentionally require most recent samples to be restricted.
        return low >= Math.max(2, (int)Math.ceil(samplesRequired * 0.67));
    }

    private void correctOnce(Player p,State s,long n,long clipped) {
        Location current=p.getLocation();
        Location target=current.clone();

        if(correctionMode==CorrectionMode.LAST_ACCEPTED
                && s.lastAccepted!=null
                && s.lastAccepted.getWorld()!=null
                && s.lastAccepted.getWorld().equals(current.getWorld())
                && current.distance(s.lastAccepted)<=maxRestoreDistance) {
            target=s.lastAccepted.clone();
            target.setYaw(current.getYaw());
            target.setPitch(current.getPitch());
        }

        final Location correctionTarget=target.clone();
        final long correctionId=++s.correctionId;

        s.lastCorrection=n;
        debug(p,"CORRECTION_ARMED id="+correctionId+" clipped="+clipped
                +" mode="+correctionMode+" target="+loc(correctionTarget));

        Bukkit.getScheduler().runTask(this, () -> {
            if(!p.isOnline() || !tracked(p)) return;

            // Exactly one correction. No repeating task.
            p.teleport(correctionTarget, PlayerTeleportEvent.TeleportCause.PLUGIN);

            long t=now();
            s.lastTeleport=t;
            s.lastAccepted=correctionTarget.clone();
            s.lastAcceptedAt=t;
            s.lastMove=t;
            s.lastMeaningfulMove=t;
            s.lastActivity=0;
            s.fails.clear();
            s.recentDeltas.clear();

            debug(p,"CORRECTION_SENT id="+correctionId+" target="+loc(correctionTarget));
        });
    }

    private boolean eligible(Player p,State s,long n) {
        if(bypassPermission!=null && !bypassPermission.isBlank() && p.hasPermission(bypassPermission)) return false;
        if(ignoreDead && p.isDead()) return false;
        if(ignoreSpectator && p.getGameMode()==GameMode.SPECTATOR) return false;
        if(ignoreVehicles && p.isInsideVehicle()) return false;
        return n-s.join>=joinGraceMs && n-s.lastTeleport>=teleportGraceMs
                && n-s.lastWorld>=worldChangeGraceMs;
    }

    private boolean tracked(Player p) { return p.isOnline() && (!bedrockOnly || isBedrock(p)); }

    private State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k->new State()); }

    private boolean nearSpecial(Player p) {
        Location l=p.getLocation();
        double r2=specialRadius*specialRadius;
        for(int x=l.getBlockX()-2;x<=l.getBlockX()+2;x++)
            for(int y=l.getBlockY()-2;y<=l.getBlockY()+2;y++)
                for(int z=l.getBlockZ()-2;z<=l.getBlockZ()+2;z++) {
                    Block b=l.getWorld().getBlockAt(x,y,z);
                    if(!specialBlocks.contains(b.getType())) continue;
                    double dx=l.getX()-(x+.5), dy=l.getY()-(y+.5), dz=l.getZ()-(z+.5);
                    if(dx*dx+dy*dy+dz*dz<=r2) return true;
                }
        return false;
    }

    private long recentClipped(State s,long n) {
        long c=0;
        for(Failure f:s.fails)
            if(n-f.time<=failWindowMs && "CLIPPED_INTO_BLOCK".equals(f.reason)) c++;
        return c;
    }

    private void trimFailures(State s,long n) {
        while(!s.fails.isEmpty() && n-s.fails.peekFirst().time>failWindowMs)
            s.fails.removeFirst();
    }

    private void trimDeltas(State s,long n) {
        while(!s.recentDeltas.isEmpty() && n-s.recentDeltas.peekFirst().time>desyncWindowMs)
            s.recentDeltas.removeFirst();
    }

    private boolean isBedrock(Player p) {
        try {
            Class<?> c=Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api=c.getMethod("getInstance").invoke(null);
            Object r=c.getMethod("isFloodgatePlayer",UUID.class).invoke(api,p.getUniqueId());
            return r instanceof Boolean && (Boolean)r;
        } catch(ReflectiveOperationException|LinkageError ex) {
            return false;
        }
    }

    private void debug(Player p,String msg) {
        if(debug) getLogger().info("[DEBUG] "+p.getName()+": "+msg);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT,"%.3f",d);
    }

    private static String loc(Location l) {
        return String.format(java.util.Locale.ROOT,"%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f",
                l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());
    }

    private static long now() { return System.currentTimeMillis(); }

    private enum CorrectionMode {
        SAME_LOCATION, LAST_ACCEPTED;
        static CorrectionMode from(String s) {
            return "last-accepted".equalsIgnoreCase(s) ? LAST_ACCEPTED : SAME_LOCATION;
        }
    }

    private static final class Failure {
        final long time; final String reason;
        Failure(long time,String reason){this.time=time;this.reason=reason;}
    }

    private static final class MovementSample {
        final long time; final double delta;
        MovementSample(long time,double delta){this.time=time;this.delta=delta;}
    }

    private static final class State {
        long join,lastMove,lastAcceptedAt,lastMeaningfulMove,lastActivity,lastTeleport,lastWorld,lastFail,lastCorrection;
        long interactions,animations,correctionId;
        double lastDelta;
        Location lastAccepted;
        final Deque<Failure> fails=new ArrayDeque<>();
        final Deque<MovementSample> recentDeltas=new ArrayDeque<>();
    }
}
