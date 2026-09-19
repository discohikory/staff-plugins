package com.discohikorybrs.discologin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Congela y silencia hasta el login.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class LoginListener implements Listener {

    private final DiscoLogin plugin;

    public LoginListener(DiscoLogin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (plugin.getConfig().getBoolean("freeze", true)) {
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
            p.setAllowFlight(false);
        }
        if (plugin.getConfig().getBoolean("blindness", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                    Integer.MAX_VALUE, 1, false, false, false));
        }
        plugin.handleJoin(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!plugin.isLogged(p) && plugin.getConfig().getBoolean("freeze", true)) {
            if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                    || e.getFrom().getBlockZ() != e.getTo().getBlockZ()
                    || e.getFrom().getBlockY() != e.getTo().getBlockY()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (!plugin.isLogged(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (plugin.isLogged(e.getPlayer())) return;
        String m = e.getMessage().toLowerCase().split(" ")[0];
        if (!m.equals("/login") && !m.equals("/register")) e.setCancelled(true);
    }

    void unfreeze(Player p) {
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }
}
