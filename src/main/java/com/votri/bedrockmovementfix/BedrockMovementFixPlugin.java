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
    private long intervalTicks, failThreshold, failWindowMs, stallMs, activityWindowMs;
    private long correctionCooldownMs, joinGraceMs, teleportGraceMs, worldChangeGraceMs;
    private double minMovementDelta, specialRadius, maxRestoreDistance;
    private CorrectionMode correctionMode;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        watchdog = Bukkit.getScheduler().runTaskTimer(this, this::watchdogTick, intervalTicks, intervalTicks);
        getLogger().info("BedrockMovementFix 2.1.1 enabled (conservative watchdog).");
        getLogger().info("No ProtocolLib/PacketEvents dependency.");
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
        stallMs = Math.max(100, getConfig().getLong("stall-ms", 350));
        activityWindowMs = Math.max(100, getConfig().getLong("activity-window-ms", 1200));
        correctionCooldownMs = Math.max(500, getConfig().getLong("correction-cooldown-ms", 3000));
        joinGraceMs = Math.max(0, getConfig().getLong("join-grace-ms", 2500));
        teleportGraceMs = Math.max(0, getConfig().getLong("teleport-grace-ms", 1200));
        worldChangeGraceMs = Math.max(0, getConfig().getLong("world-change-grace-ms", 1200));
        minMovementDelta = Math.max(0, getConfig().getDouble("minimum-movement-delta", 0.02));
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
    public void join(PlayerJoinEvent e) {
        Player p=e.getPlayer(); long n=now();
        State s=new State(); s.join=n; s.lastMove=n; s.lastActivity=n; s.lastAccepted=p.getLocation().clone();
        states.put(p.getUniqueId(),s); debug(p,"JOIN bedrock="+isBedrock(p));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void move(PlayerMoveEvent e) {
        if(!enabled) return; Player p=e.getPlayer(); if(!tracked(p)) return;
        Location to=e.getTo(); if(to==null || to.getWorld()==null) return;
        State s=states.computeIfAbsent(p.getUniqueId(), k->new State()); long n=now();
        double d=(s.lastAccepted!=null && s.lastAccepted.getWorld()!=null
                && s.lastAccepted.getWorld().equals(to.getWorld()))
                ? s.lastAccepted.distance(to) : 0;
        s.lastAccepted=to.clone(); s.lastAcceptedAt=n; s.lastMove=n; s.lastDelta=d;
        if(d >= minMovementDelta) s.lastMovementActivity=n;
        debug(p,"MOVE delta="+fmt(d)+" special="+nearSpecial(p));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void teleport(PlayerTeleportEvent e) {
        Player p=e.getPlayer(); if(!tracked(p)) return; long n=now();
        State s=states.computeIfAbsent(p.getUniqueId(),k->new State());
        s.lastTeleport=n; s.lastActivity=n; s.fails.clear();
        if(e.getTo()!=null){s.lastAccepted=e.getTo().clone();s.lastAcceptedAt=n;s.lastMove=n;}
        debug(p,"TELEPORT cause="+e.getCause());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent e) {
        Player p=e.getPlayer(); if(!tracked(p)) return; long n=now();
        State s=states.computeIfAbsent(p.getUniqueId(),k->new State());
        s.lastWorld=n; s.lastActivity=n; s.fails.clear(); s.lastAccepted=p.getLocation().clone();
        s.lastAcceptedAt=n; s.lastMove=n;
        debug(p,"WORLD_CHANGE world="+p.getWorld().getName());
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void interact(PlayerInteractEvent e) {
        Player p=e.getPlayer(); if(!tracked(p)) return; State s=state(p);
        s.lastActivity=now(); s.interactions++;
        debug(p,"INTERACT action="+e.getAction()+" block="+(e.getClickedBlock()==null?"none":e.getClickedBlock().getType()));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void animation(PlayerAnimationEvent e) {
        Player p=e.getPlayer(); if(!tracked(p)) return; State s=state(p);
        s.lastActivity=now(); s.animations++;
        debug(p,"ANIMATION type="+e.getAnimationType());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void fail(PlayerFailMoveEvent e) {
        if(!enabled) return; Player p=e.getPlayer(); if(!tracked(p)) return;
        State s=state(p); long n=now();
        s.lastFail=n; s.lastActivity=n; // fail itself is meaningful activity
        s.fails.addLast(new Failure(n,e.getFailReason().name()));
        trim(s,n);
        if("CLIPPED_INTO_BLOCK".equals(e.getFailReason().name())) s.clipped++;
        debug(p,"FAIL_MOVE reason="+e.getFailReason()+" clippedRecent="+recentClipped(s,n));
        // Intentionally do NOT call e.setAllowed(true).
    }

    private void watchdogTick() {
        if(!enabled) return; long n=now();
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(!tracked(p)) continue; State s=states.get(p.getUniqueId()); if(s==null) continue;
            if(!eligible(p,s,n)) continue;
            if(!nearSpecial(p)) continue;
            long clipped=recentClipped(s,n);
            boolean failureBurst=clipped>=failThreshold;
            boolean movementStall=n-s.lastMove>=stallMs;
            boolean activity=n-s.lastActivity<=activityWindowMs;
            boolean correctionReady=n-s.lastCorrection>=correctionCooldownMs;
            boolean meaningfulMotion=s.lastDelta<minMovementDelta || n-s.lastMovementActivity>=stallMs;
            debug(p,"WATCH clipped="+clipped+" stall="+movementStall+" activity="+activity+" motionLow="+meaningfulMotion);
            if(failureBurst && movementStall && activity && correctionReady && meaningfulMotion) {
                correctOnce(p,s,n,clipped);
            }
        }
    }

    private void correctOnce(Player p, State s, long n, long clipped) {
        Location current=p.getLocation();
        Location target=current.clone();
        if(correctionMode==CorrectionMode.LAST_ACCEPTED && s.lastAccepted!=null
                && s.lastAccepted.getWorld()!=null && s.lastAccepted.getWorld().equals(current.getWorld())
                && current.distance(s.lastAccepted)<=maxRestoreDistance) {
            target=s.lastAccepted.clone(); target.setYaw(current.getYaw()); target.setPitch(current.getPitch());
        }
        s.lastCorrection=n; s.correctionId++;
        long id=s.correctionId;
        debug(p,"CORRECTION_ARMED id="+id+" clipped="+clipped+" mode="+correctionMode);
        Bukkit.getScheduler().runTask(this,()->{
            if(!p.isOnline() || !tracked(p)) return;
            // One-shot only. No repeating task and no fail-move allowance.
            p.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            long t=now(); s.lastTeleport=t; s.lastAccepted=target.clone(); s.lastAcceptedAt=t; s.lastMove=t;
            s.lastActivity=0; s.fails.clear();
            debug(p,"CORRECTION_SENT id="+id+" target="+loc(target));
        });
    }

    private boolean eligible(Player p,State s,long n) {
        if(p.hasPermission(bypassPermission)) return false;
        if(ignoreDead&&p.isDead()) return false;
        if(ignoreSpectator&&p.getGameMode()==GameMode.SPECTATOR) return false;
        if(ignoreVehicles&&p.isInsideVehicle()) return false;
        return n-s.join>=joinGraceMs && n-s.lastTeleport>=teleportGraceMs && n-s.lastWorld>=worldChangeGraceMs;
    }

    private boolean tracked(Player p){ return p.isOnline() && (!bedrockOnly || isBedrock(p)); }
    private State state(Player p){return states.computeIfAbsent(p.getUniqueId(),k->new State());}

    private boolean nearSpecial(Player p){
        Location l=p.getLocation(); double r2=specialRadius*specialRadius;
        for(int x=l.getBlockX()-2;x<=l.getBlockX()+2;x++)
            for(int y=l.getBlockY()-2;y<=l.getBlockY()+2;y++)
                for(int z=l.getBlockZ()-2;z<=l.getBlockZ()+2;z++){
                    Block b=l.getWorld().getBlockAt(x,y,z); if(!specialBlocks.contains(b.getType())) continue;
                    double dx=l.getX()-(x+.5),dy=l.getY()-(y+.5),dz=l.getZ()-(z+.5);
                    if(dx*dx+dy*dy+dz*dz<=r2)return true;
                }
        return false;
    }

    private long recentClipped(State s,long n){
        long c=0; for(Failure f:s.fails) if(n-f.time<=failWindowMs && "CLIPPED_INTO_BLOCK".equals(f.reason)) c++;
        return c;
    }
    private void trim(State s,long n){while(!s.fails.isEmpty()&&n-s.fails.peekFirst().time>failWindowMs)s.fails.removeFirst();}
    private boolean isBedrock(Player p){
        try{
            Class<?> c=Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api=c.getMethod("getInstance").invoke(null);
            Object r=c.getMethod("isFloodgatePlayer",UUID.class).invoke(api,p.getUniqueId());
            return r instanceof Boolean && (Boolean)r;
        }catch(ReflectiveOperationException|LinkageError ex){return false;}
    }
    private void debug(Player p,String s){if(debug)getLogger().info("[DEBUG] "+p.getName()+": "+s);}
    private static String fmt(double d){return String.format(java.util.Locale.ROOT,"%.3f",d);}
    private static String loc(Location l){return String.format(java.util.Locale.ROOT,"%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f",l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());}
    private static long now(){return System.currentTimeMillis();}

    private enum CorrectionMode{SAME_LOCATION,LAST_ACCEPTED;
        static CorrectionMode from(String s){return "last-accepted".equalsIgnoreCase(s)?LAST_ACCEPTED:SAME_LOCATION;}
    }
    private static final class Failure{
        final long time; final String reason; Failure(long t,String r){time=t;reason=r;}
    }
    private static final class State{
        long join,lastMove,lastAcceptedAt,lastMovementActivity,lastActivity,lastTeleport,lastWorld,lastFail,lastCorrection;
        long interactions,animations,clipped,correctionId; double lastDelta;
        Location lastAccepted; final Deque<Failure> fails=new ArrayDeque<>();
    }
}
