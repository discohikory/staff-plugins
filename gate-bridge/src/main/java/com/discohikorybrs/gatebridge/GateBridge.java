package com.discohikorybrs.gatebridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puerta: el menú 2FA (Skylith) solo existe con el permiso.
 * xLogin congela a los no logeados, así que moverse = logeado.
 * Al moverse 2+ bloques se otorga el permiso; al salir se retira.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class GateBridge extends JavaPlugin implements Listener {

    private String perm;
    private double minDistance;
    private final Map<UUID, Location> joinLoc = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> granted = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        perm = getConfig().getString("gate-permission", "skylith.2fa.required");
        minDistance = getConfig().getDouble("min-distance-blocks", 2.0);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("GateBridge v1.0.0 por Discohikorybrs activado (" + perm + ").");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinLoc.put(p.getUniqueId(), p.getLocation().clone());
        granted.put(p.getUniqueId(), false);
        // Sin permiso no hay menú antes del login
        dispatch("lp user " + p.getName() + " permission unset " + perm);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        joinLoc.remove(u);
        granted.remove(u);
        dispatch("lp user " + e.getPlayer().getName() + " permission unset " + perm);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (granted.getOrDefault(p.getUniqueId(), true)) return;
        Location j = joinLoc.get(p.getUniqueId());
        if (j == null || !j.getWorld().equals(p.getWorld())) return;
        double dx = p.getLocation().getX() - j.getX();
        double dz = p.getLocation().getZ() - j.getZ();
        if (dx * dx + dz * dz >= minDistance * minDistance) {
            granted.put(p.getUniqueId(), true);
            dispatch("lp user " + p.getName() + " permission set " + perm + " true");
            getLogger().info("Puerta abierta para " + p.getName());
        }
    }

    private void dispatch(String command) {
        try {
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } catch (Exception ignored) {}
    }
}
