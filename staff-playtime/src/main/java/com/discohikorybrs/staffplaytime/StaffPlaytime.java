package com.discohikorybrs.staffplaytime;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Modo jugador por tiempo para staffs: quita permisos, pausa, resets y avisa a Discord.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class StaffPlaytime extends JavaPlugin implements Listener, CommandExecutor {

    private enum State { STAFF, PLAYER, PAUSED }

    private LuckPerms luckPerms;
    private JDA jda;
    private FileConfiguration data;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (Exception e) {
            getLogger().severe("LuckPerms no encontrado. Desactivando.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        String token = getConfig().getString("discord.token", "");
        if (token != null && token.length() > 20) {
            try {
                jda = JDABuilder.createDefault(token, GatewayIntent.GUILD_MESSAGES)
                        .build().awaitReady();
                getLogger().info("Discord conectado.");
            } catch (Exception e) {
                getLogger().warning("Sin Discord: " + e.getMessage());
            }
        }
        getCommand("stplaytime").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        // Tarea cada segundo: descuenta tiempo en modo PLAYER
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (stateOf(p.getUniqueId()) != State.PLAYER) continue;
                long left = remaining(p.getUniqueId()) - 1000L;
                setRemaining(p.getUniqueId(), Math.max(0, left));
                if (left <= 0) {
                    toStaffMode(p, "⏰ **" + p.getName() + "** terminó su tiempo de jugador.");
                }
            }
            saveData();
        }, 20L, 20L);
        getLogger().info("StaffPlaytime v1.0.0 por Discohikorybrs activado.");
    }

    @Override
    public void onDisable() {
        saveData();
        if (jda != null) jda.shutdown();
    }

    // ---------- Datos ----------
    private String base(UUID u) {
        return "players." + u + ".";
    }

    private void ensure(UUID u) {
        if (!data.contains(base(u) + "state")) {
            data.set(base(u) + "state", State.STAFF.name());
            data.set(base(u) + "remaining", getConfig().getLong("total-minutes", 150) * 60000L);
            data.set(base(u) + "uses", getConfig().getInt("max-uses", 3));
        }
    }

    private State stateOf(UUID u) {
        ensure(u);
        try {
            return State.valueOf(data.getString(base(u) + "state", "STAFF"));
        } catch (Exception e) {
            return State.STAFF;
        }
    }

    private long remaining(UUID u) {
        ensure(u);
        return data.getLong(base(u) + "remaining", 0);
    }

    private void setRemaining(UUID u, long ms) {
        ensure(u);
        data.set(base(u) + "remaining", ms);
    }

    private int uses(UUID u) {
        ensure(u);
        return data.getInt(base(u) + "uses", 0);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar players.yml");
        }
    }

    private String msg(String path) {
        return getConfig().getString("messages.prefix", "")
                + getConfig().getString("messages." + path, "");
    }

    private static String fmt(long ms) {
        long m = ms / 60000L;
        return (m / 60) + "h " + (m % 60) + "m";
    }

    /** "30m", "1h", "1h30m" -> milisegundos. -1 si inválido. */
    static long parseTime(String s) {
        try {
            s = s.toLowerCase().replace(" ", "");
            long total = 0;
            String num = "";
            boolean any = false;
            for (char c : (s + " ").toCharArray()) {
                if (Character.isDigit(c)) {
                    num += c;
                } else if ((c == 'h' || c == 'm') && !num.isEmpty()) {
                    total += Long.parseLong(num) * (c == 'h' ? 3600000L : 60000L);
                    num = "";
                    any = true;
                } else if (c == ' ') {
                    continue;
                } else {
                    return -1;
                }
            }
            return any ? total : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- LuckPerms ----------
    private void toPlayerMode(Player p) {
        UUID u = p.getUniqueId();
        UserManager um = luckPerms.getUserManager();
        um.loadUser(u).thenAcceptAsync(user -> {
            List<String> staff = getConfig().getStringList("staff-groups");
            for (String g : staff) user.data().remove(Node.builder("group." + g).build());
            String pg = getConfig().getString("player-group", "jugador");
            // Guardar grupos originales para restaurar
            StringBuilder orig = new StringBuilder();
            for (String g : staff) {
                if (user.getInheritedGroups(user.getQueryOptions()).stream()
                        .anyMatch(gr -> gr.getName().equalsIgnoreCase(g))) {
                    if (orig.length() > 0) orig.append(",");
                    orig.append(g);
                }
            }
            data.set(base(u) + "orig-groups", orig.toString());
            user.data().add(Node.builder("group." + pg).build());
            um.saveUser(user);
            saveData();
        });
    }

    private void toStaffMode(Player p, String discordMsg) {
        UUID u = p.getUniqueId();
        UserManager um = luckPerms.getUserManager();
        um.loadUser(u).thenAcceptAsync(user -> {
            String pg = getConfig().getString("player-group", "jugador");
            user.data().remove(Node.builder("group." + pg).build());
            String orig = data.getString(base(u) + "orig-groups", "");
            for (String g : orig.split(",")) {
                if (!g.trim().isEmpty()) user.data().add(Node.builder("group." + g.trim()).build());
            }
            um.saveUser(user);
        });
        data.set(base(u) + "state", State.STAFF.name());
        data.set(base(u) + "remaining", 0L);
        saveData();
        p.sendMessage(msg("time-up"));
        postSalida(p.getName(), discordMsg != null ? discordMsg
                : "⏰ **" + p.getName() + "** volvió a modo staff.");
    }

    // ---------- Discord ----------
    private void post(boolean entrada, String title, String desc) {
        if (jda == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = getConfig().getString(entrada
                ? "discord.entrada-channel-id" : "discord.salida-channel-id", "");
        if (chId == null || chId.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) return;
                TextChannel ch = g.getTextChannelById(chId);
                if (ch == null) return;
                ch.sendMessageEmbeds(new EmbedBuilder()
                        .setTitle(title).setDescription(desc)
                        .setColor(entrada ? new Color(46, 255, 161) : new Color(255, 106, 0))
                        .build()).queue();
            } catch (Exception e) {
                getLogger().warning("Discord: " + e.getMessage());
            }
        });
    }

    private void postEntrada(String n, String extra) {
        post(true, "🩷 Entrada de turno", "**" + n + "** entró en modo jugador. " + extra);
    }

    private void postSalida(String n, String extra) {
        post(false, "🩷 Salida de turno", "**" + n + "** " + extra);
    }

    // ---------- Salida sin pausar = auto-pausa ----------
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (stateOf(p.getUniqueId()) == State.PLAYER) {
            data.set(base(p.getUniqueId()) + "state", State.PAUSED.name());
            saveData();
            postSalida(p.getName(), "se desconectó sin pausar: **tiempo auto-pausado** ⏸️.");
        }
    }

    // ---------- Comandos ----------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("uses") && a[1].equalsIgnoreCase("reset")) {
            if (!s.hasPermission("staffplaytime.admin")) {
                s.sendMessage("§cSolo superior a Manager.");
                return true;
            }
            OfflinePlayer t = Bukkit.getOfflinePlayer(a[2]);
            data.set(base(t.getUniqueId()) + "uses", getConfig().getInt("max-uses", 3));
            saveData();
            s.sendMessage(msg("uses-reset").replace("{jugador}", a[2]));
            return true;
        }
        if (a.length >= 3 && a[0].equalsIgnoreCase("time") && a[1].equalsIgnoreCase("reset")) {
            if (!s.hasPermission("staffplaytime.admin")) {
                s.sendMessage("§cSolo superior a Manager.");
                return true;
            }
            OfflinePlayer t = Bukkit.getOfflinePlayer(a[2]);
            data.set(base(t.getUniqueId()) + "remaining",
                    getConfig().getLong("total-minutes", 150) * 60000L);
            saveData();
            s.sendMessage(msg("time-reset").replace("{jugador}", a[2]));
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Solo jugadores (los resets aceptan consola con permiso).");
            return true;
        }
        Player p = (Player) s;
        UUID u = p.getUniqueId();
        if (a.length == 0) {
            p.sendMessage(msg("remaining")
                    .replace("{tiempo}", fmt(remaining(u)))
                    .replace("{usos}", String.valueOf(uses(u))));
            return true;
        }
        if (a[0].equalsIgnoreCase("pause")) {
            if (stateOf(u) == State.PAUSED) {
                p.sendMessage(msg("already-paused"));
                return true;
            }
            if (stateOf(u) != State.PLAYER) {
                p.sendMessage(msg("not-active"));
                return true;
            }
            data.set(base(u) + "state", State.PAUSED.name());
            saveData();
            p.sendMessage(msg("paused"));
            // Cuenta como SALIDA pero avisando que es pausa
            postSalida(p.getName(), "pausó su tiempo ⏸️ (cuenta como salida, quedan "
                    + fmt(remaining(u)) + ").");
            return true;
        }
        if (a[0].equalsIgnoreCase("despause")) {
            if (stateOf(u) != State.PAUSED) {
                p.sendMessage(msg("not-paused"));
                return true;
            }
            data.set(base(u) + "state", State.PLAYER.name());
            saveData();
            p.sendMessage(msg("unpaused"));
            postEntrada(p.getName(), "reanudó su tiempo ▶️.");
            return true;
        }
        long ms = parseTime(a[0]);
        if (ms <= 0) {
            p.sendMessage("§eUso: /stplaytime <tiempo: 30m|1h> | pause | despause");
            return true;
        }
        if (uses(u) <= 0) {
            p.sendMessage(msg("no-uses"));
            return true;
        }
        if (stateOf(u) == State.PLAYER) {
            p.sendMessage("§cYa estás en modo jugador. Usa pause primero.");
            return true;
        }
        long total = getConfig().getLong("total-minutes", 150) * 60000L;
        if (ms > total) ms = total;
        data.set(base(u) + "uses", uses(u) - 1);
        data.set(base(u) + "remaining", ms);
        data.set(base(u) + "state", State.PLAYER.name());
        saveData();
        toPlayerMode(p);
        p.sendMessage(msg("started")
                .replace("{tiempo}", fmt(ms))
                .replace("{usos}", String.valueOf(uses(u))));
        postEntrada(p.getName(), "modo jugador por " + fmt(ms) + ".");
        return true;
    }
}
