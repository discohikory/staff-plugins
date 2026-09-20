package com.discohikorybrs.syncvinculacion;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Al entrar revisa desvinculación (perdió rango staff).
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class JoinCheck implements Listener {

    private final SyncVinculacion plugin;

    public JoinCheck(SyncVinculacion plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> {
                    if (e.getPlayer().isOnline()) plugin.checkPlayer(e.getPlayer());
                }, 100L);
    }
}
