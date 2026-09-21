package com.discohikorybrs.gatebridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.permission.Permission;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puerta inteligente del 2FA:
 * - Premium (Mojang) = entra directo, SIN 2FA.
 * - No premium + rango staff = se abre la puerta del menú tras logearse.
 * - OP no cuenta como staff: solo valen grupos de la escalera.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class GateBridge extends JavaPlugin implements Listener {

    private String perm;
    private double minDistance;
    private List<String> staffGroups;
    private Permission vault;
    private final Map<UUID, Location> joinLoc = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> granted = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> premiumCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        perm = getConfig().getString("gate-permission", "skylith.2fa.required");
        minDistance = getConfig().getDouble("min-distance-blocks", 2.0);
        staffGroups = getConfig().getStringList("staff-groups");
        try {
            RegisteredServiceProvider<Permission> rsp =
                    getServer().getServicesManager().getRegistration(Permission.class);
            if (rsp != null) vault = rsp.getProvider();
        } catch (Exception ignored) {}
        if (vault == null) getLogger().warning("Sin Vault: la puerta no detectará rangos.");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("GateBridge v1.0.0 por Discohikorybrs activado (" + perm + ").");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinLoc.put(p.getUniqueId(), p.getLocation().clone());
        granted.put(p.getUniqueId(), false);
        // Negación explícita: sin menú antes del login
        dispatch("lp user " + p.getName() + " permission set " + perm + " false");
        // Veredicto premium en segundo plano
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean premium = isPremium(p.getName(), p.getUniqueId());
            premiumCache.put(p.getUniqueId(), premium);
            if (premium) getLogger().info(p.getName() + " es premium: salta el 2FA.");
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        joinLoc.remove(u);
        granted.remove(u);
        premiumCache.remove(u);
        dispatch("lp user " + e.getPlayer().getName() + " permission set " + perm + " false");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (granted.getOrDefault(p.getUniqueId(), true)) return;
        Location j = joinLoc.get(p.getUniqueId());
        if (j == null || !j.getWorld().equals(p.getWorld())) return;
        double dx = p.getLocation().getX() - j.getX();
        double dz = p.getLocation().getZ() - j.getZ();
        if (dx * dx + dz * dz < minDistance * minDistance) return;
        // Se movió = logeado. ¿Staff no premium?
        if (Boolean.TRUE.equals(premiumCache.get(p.getUniqueId()))) return;
        if (!isStaff(p)) return;
        granted.put(p.getUniqueId(), true);
        dispatch("lp user " + p.getName() + " permission set " + perm + " true");
        getLogger().info("Puerta abierta para " + p.getName());
    }

    /** Staff = grupo primario en la escalera. OP no cuenta. */
    private boolean isStaff(Player p) {
        try {
            if (vault == null) return p.hasPermission("authstaff.required");
            String[] groups = vault.getPlayerGroups(p);
            for (String g : groups) {
                for (String s : staffGroups) {
                    if (s.equalsIgnoreCase(g)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Premium = Mojang confirma el nick Y el UUID coincide. Bedrock nunca. */
    private boolean isPremium(String name, UUID uuid) {
        try {
            if (name.startsWith(".")) return false;
            java.net.URL url = new java.net.URL(
                    "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
                    + java.net.URLEncoder.encode(name, "UTF-8"));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return false;
            }
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");
            c.disconnect();
            String id = body.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f]{32})\".*", "$1");
            if (id.length() != 32) return false;
            UUID real = UUID.fromString(id.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            return real.equals(uuid);
        } catch (Exception e) {
            return false;
        }
    }

    private void dispatch(String command) {
        try {
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } catch (Exception ignored) {}
    }
}
