package com.discohikorybrs.authstaff;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Núcleo del plugin: flujo de login, verificación y comandos.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class AuthStaff extends JavaPlugin implements Listener, CommandExecutor {

    private static AuthStaff instance;
    private final java.util.Map<UUID, Boolean> verified = new java.util.concurrent.ConcurrentHashMap<>();
    private FileConfiguration data;
    private File dataFile;
    private CodeMenu menu;
    private boolean authMePresent;

    public static AuthStaff get() {
        return instance;
    }

    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        menu = new CodeMenu(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menu, this);
        getCommand("auth").setExecutor(this);

        authMePresent = getServer().getPluginManager().getPlugin("AuthMe") != null;
        if (authMePresent) {
            try {
                Class<?> ev = Class.forName("fr.xephi.authme.events.LoginEvent");
                @SuppressWarnings("unchecked")
                Class<? extends org.bukkit.event.Event> evClass =
                        (Class<? extends org.bukkit.event.Event>) ev;
                getServer().getPluginManager().registerEvent(evClass, this,
                        org.bukkit.event.EventPriority.NORMAL,
                        (listener, event) -> {
                            try {
                                Method m = ev.getMethod("getPlayer");
                                Player p = (Player) m.invoke(event);
                                Bukkit.getScheduler().runTask(this, () -> startFlow(p));
                            } catch (Exception ignored) {}
                        }, this);
                getLogger().info("AuthMe detectado: el 2FA inicia tras el login.");
            } catch (Exception e) {
                authMePresent = false;
            }
        }
        getLogger().info("AuthStaff v1.0.0 por Discohikorybrs activado.");
    }

    public void onDisable() {
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar data.yml");
        }
    }

    private String key(UUID u) {
        return "players." + u + ".secret";
    }

    public boolean requires(Player p) {
        return p.hasPermission("authstaff.required") && !p.hasPermission("authstaff.admin");
    }

    public boolean isVerified(Player p) {
        return verified.getOrDefault(p.getUniqueId(), false);
    }

    private String msg(String path) {
        return getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + path, "");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        verified.remove(p.getUniqueId());
        if (!authMePresent) {
            // Sin AuthMe: iniciar el flujo 2s después de entrar
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) startFlow(p);
            }, 40L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        verified.remove(e.getPlayer().getUniqueId());
        menu.clear(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!requires(p) || isVerified(p)) return;
        if (!getConfig().getBoolean("freeze-until-verified", true)) return;
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            e.setCancelled(true);
            p.sendMessage(msg("need-verify"));
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!requires(p) || isVerified(p)) return;
        if (e.getMessage().matches("\\d{6}")) {
            e.setCancelled(true);
            final String code = e.getMessage();
            Bukkit.getScheduler().runTask(this, () -> tryCode(p, code));
        }
    }

    /** Inicia el flujo: genera secreto si no existe y entrega el mapa QR. */
    public void startFlow(Player p) {
        if (!requires(p) || isVerified(p) || !p.isOnline()) return;
        String secret = data.getString(key(p.getUniqueId()));
        if (secret == null) {
            secret = TotpUtil.generateSecret();
            data.set(key(p.getUniqueId()), secret);
            saveData();
        }
        String uri = TotpUtil.otpAuthUri(secret, p.getName(), getConfig().getString("issuer", "Staff"));
        QrMapUtil.giveQrMap(this, p, uri);
        p.sendMessage(msg("need-verify"));
        p.sendMessage(msg("enter-code"));
    }

    /** Valida un código de 6 dígitos. */
    public void tryCode(Player p, String code) {
        if (!requires(p)) return;
        if (isVerified(p)) {
            p.sendMessage(msg("already"));
            return;
        }
        String secret = data.getString(key(p.getUniqueId()));
        if (secret == null) {
            startFlow(p);
            return;
        }
        if (TotpUtil.verify(secret, code)) {
            verified.put(p.getUniqueId(), true);
            menu.clear(p.getUniqueId());
            removeQrMap(p);
            p.sendMessage(msg("success"));
            if (getConfig().getBoolean("teleport-on-success", true)) {
                Location lobby = lobby();
                if (lobby != null) p.teleport(lobby);
            }
        } else {
            p.sendMessage(msg("wrong-code"));
        }
    }

    private void removeQrMap(Player p) {
        for (int i = 0; i < 36; i++) {
            org.bukkit.inventory.ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType() != org.bukkit.Material.FILLED_MAP) continue;
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                    && it.getItemMeta().getDisplayName().contains("Código QR 2FA")) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    private Location lobby() {
        String w = getConfig().getString("lobby.world", "world");
        World world = Bukkit.getWorld(w);
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("lobby.x", 0.5),
                getConfig().getDouble("lobby.y", 100),
                getConfig().getDouble("lobby.z", 0.5),
                (float) getConfig().getDouble("lobby.yaw", 0),
                (float) getConfig().getDouble("lobby.pitch", 0));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("reset")) {
            if (!s.hasPermission("authstaff.admin")) {
                s.sendMessage(msg("no-permission"));
                return true;
            }
            Player t = Bukkit.getPlayer(a[1]);
            if (t == null) {
                s.sendMessage("§cJugador no encontrado.");
                return true;
            }
            String secret = TotpUtil.generateSecret();
            data.set(key(t.getUniqueId()), secret);
            saveData();
            verified.remove(t.getUniqueId());
            s.sendMessage(msg("reset-done"));
            if (t.isOnline()) startFlow(t);
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Solo jugadores.");
            return true;
        }
        Player p = (Player) s;
        if (a.length == 0 || a[0].equalsIgnoreCase("menu")) {
            if (!requires(p) && !isVerified(p)) startFlow(p);
            menu.open(p);
            return true;
        }
        if (a[0].equalsIgnoreCase("qr")) {
            startFlow(p);
            return true;
        }
        if (a[0].matches("\\d{6}")) {
            tryCode(p, a[0]);
            return true;
        }
        p.sendMessage("§eUso: /auth [codigo|menu|qr]");
        return true;
    }

    /** Jugadores con sesión verificada (para otros plugins). */
    public Set<UUID> verifiedPlayers() {
        Set<UUID> out = new HashSet<>();
        verified.forEach((u, v) -> { if (v) out.add(u); });
        return out;
    }
}
