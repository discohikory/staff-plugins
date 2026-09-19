package com.discohikorybrs.discologin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro, login, sesiones y premium. Congela hasta validar.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class DiscoLogin extends JavaPlugin implements CommandExecutor {

    private AuthManager auth;
    private final Set<UUID> logged = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> tasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        auth = new AuthManager(this);
        try {
            auth.open();
        } catch (Exception e) {
            getLogger().severe("Sin base de datos. Desactivando.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new LoginListener(this), this);
        for (String c : new String[]{"register", "login", "changepassword", "unregister", "premium"})
            getCommand(c).setExecutor(this);
        getLogger().info("DiscoLogin v1.0.0 por Discohikorybrs activado.");
    }

    @Override
    public void onDisable() {
        if (auth != null) auth.close();
    }

    public AuthManager auth() {
        return auth;
    }

    public boolean isLogged(Player p) {
        return logged.contains(p.getUniqueId());
    }

    private String msg(String path) {
        return getConfig().getString("messages.prefix", "")
                + getConfig().getString("messages." + path, "");
    }

    private String ipOf(Player p) {
        try {
            return p.getAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "?";
        }
    }

    /** Llamado al entrar: decide auto-login, sesión o pedir clave. */
    void handleJoin(Player p) {
        logged.remove(p.getUniqueId());
        cancelTask(p);
        String ip = ipOf(p);
        AuthManager.Account a = auth.get(p.getName());
        // Premium con auto-login: verifica Mojang en async
        if (getConfig().getBoolean("premium-auto-login", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                if (AuthManager.isPremium(p.getName())) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (!p.isOnline()) return;
                        forceLogin(p, msg("premium-welcome"));
                    });
                } else {
                    Bukkit.getScheduler().runTask(this, () -> prompt(p, a, ip));
                }
            });
            return;
        }
        prompt(p, a, ip);
    }

    private void prompt(Player p, AuthManager.Account a, String ip) {
        if (!p.isOnline()) return;
        if (a == null) {
            p.sendMessage(msg("need-register"));
        } else {
            int days = getConfig().getInt("session-days", 7);
            long maxAge = days * 86400000L;
            if (days > 0 && ip.equals(a.ip)
                    && System.currentTimeMillis() - a.lastLogin < maxAge) {
                forceLogin(p, msg("session"));
                return;
            }
            p.sendMessage(msg("need-login"));
        }
        int timeout = getConfig().getInt("login-timeout", 120);
        if (timeout > 0) {
            int id = Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !isLogged(p)) p.kickPlayer(msg("timeout"));
            }, timeout * 20L).getTaskId();
            tasks.put(p.getUniqueId(), id);
        }
    }

    void handleQuit(Player p) {
        logged.remove(p.getUniqueId());
        cancelTask(p);
    }

    private void cancelTask(Player p) {
        Integer id = tasks.remove(p.getUniqueId());
        if (id != null) Bukkit.getScheduler().cancelTask(id);
    }

    private void forceLogin(Player p, String hello) {
        logged.add(p.getUniqueId());
        cancelTask(p);
        auth.touch(p.getName(), ipOf(p));
        new LoginListener(this).unfreeze(p);
        p.sendMessage(hello);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        if (n.equals("unregister")) {
            if (a.length < 1) {
                s.sendMessage("§eUso: /unregister <jugador>");
                return true;
            }
            auth.delete(a[0]);
            s.sendMessage(msg("unregistered").replace("{jugador}", a[0]));
            return true;
        }
        if (n.equals("premium")) {
            if (a.length < 1) {
                s.sendMessage("§eUso: /premium <jugador>");
                return true;
            }
            AuthManager.Account acc = auth.get(a[0]);
            auth.save(a[0], acc == null ? "" : acc.hash, acc == null ? "" : acc.salt,
                    acc == null ? "" : acc.ip, true);
            s.sendMessage(msg("premium-set").replace("{jugador}", a[0]));
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Solo jugadores.");
            return true;
        }
        Player p = (Player) s;
        String ip = ipOf(p);
        if (n.equals("register")) {
            if (auth.get(p.getName()) != null) {
                p.sendMessage(msg("already"));
                return true;
            }
            if (a.length < 2) {
                p.sendMessage(msg("need-register"));
                return true;
            }
            if (!a[0].equals(a[1])) {
                p.sendMessage(msg("mismatch"));
                return true;
            }
            int min = getConfig().getInt("min-password-length", 5);
            if (a[0].length() < min) {
                p.sendMessage(msg("short").replace("{n}", String.valueOf(min)));
                return true;
            }
            if (auth.countByIp(ip) >= getConfig().getInt("max-accounts-per-ip", 3)) {
                p.sendMessage(msg("max-ip"));
                return true;
            }
            String salt = AuthManager.salt();
            auth.save(p.getName(), AuthManager.hash(salt, a[0]), salt, ip, false);
            forceLogin(p, msg("registered"));
            return true;
        }
        if (n.equals("login")) {
            if (isLogged(p)) return true;
            AuthManager.Account acc = auth.get(p.getName());
            if (acc == null || acc.hash == null || acc.hash.isEmpty()) {
                p.sendMessage(msg("no-account"));
                return true;
            }
            if (a.length < 1 || !AuthManager.hash(acc.salt, a[0]).equals(acc.hash)) {
                p.sendMessage(msg("wrong"));
                return true;
            }
            forceLogin(p, msg("logged"));
            return true;
        }
        if (n.equals("changepassword")) {
            AuthManager.Account acc = auth.get(p.getName());
            if (acc == null) {
                p.sendMessage(msg("no-account"));
                return true;
            }
            if (a.length < 2 || !AuthManager.hash(acc.salt, a[0]).equals(acc.hash)) {
                p.sendMessage(msg("wrong"));
                return true;
            }
            int min = getConfig().getInt("min-password-length", 5);
            if (a[1].length() < min) {
                p.sendMessage(msg("short").replace("{n}", String.valueOf(min)));
                return true;
            }
            String salt = AuthManager.salt();
            auth.save(p.getName(), AuthManager.hash(salt, a[1]), salt, ip, acc.premium);
            p.sendMessage(msg("changed"));
            return true;
        }
        return false;
    }
}
