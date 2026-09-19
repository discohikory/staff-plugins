package com.discohikorybrs.syncvinculacion;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vincula MC↔Discord y sincroniza /promote y /demote con LuckPerms + Discord.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class SyncVinculacion extends JavaPlugin implements CommandExecutor {

    private LuckPerms luckPerms;
    private JDA jda;
    private FileConfiguration links;
    private File linksFile;

    public static class Rank {
        String group, display, roleId;
    }

    private final List<Rank> ladder = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLadder();
        linksFile = new File(getDataFolder(), "links.yml");
        links = YamlConfiguration.loadConfiguration(linksFile);
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (Exception e) {
            getLogger().severe("LuckPerms no encontrado. Desactivando.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        String token = getConfig().getString("discord.token", "");
        if (token == null || token.length() < 20) {
            getLogger().warning("Sin token de Discord: funcionará solo LuckPerms hasta configurarlo.");
        } else {
            try {
                jda = JDABuilder.createDefault(token,
                                GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.DIRECT_MESSAGES)
                        .build().awaitReady();
                getLogger().info("Discord conectado como " + jda.getSelfUser().getAsTag());
            } catch (Exception e) {
                getLogger().warning("No se pudo conectar a Discord: " + e.getMessage());
            }
        }
        getCommand("stafflinkdiscord").setExecutor(this);
        getCommand("promote").setExecutor(this);
        getCommand("demote").setExecutor(this);
        getLogger().info("SyncVinculacion v1.0.0 por Discohikorybrs activado.");
    }

    @Override
    public void onDisable() {
        saveLinks();
        if (jda != null) jda.shutdown();
    }

    private void loadLadder() {
        ladder.clear();
        for (Map<?, ?> m : getConfig().getMapList("ladder")) {
            Rank r = new Rank();
            r.group = String.valueOf(m.get("group"));
            r.display = String.valueOf(m.get("display"));
            Object ro = m.get("discord-role-id");
            r.roleId = ro == null ? "" : String.valueOf(ro);
            ladder.add(r);
        }
    }

    private void saveLinks() {
        try {
            links.save(linksFile);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar links.yml");
        }
    }

    private String msg(String path) {
        return getConfig().getString("messages.prefix", "")
                + getConfig().getString("messages." + path, "");
    }

    /** Índice del rango actual del jugador en la escalera, -1 si ninguno. */
    private int currentRank(net.luckperms.api.model.user.User u) {
        String primary = u.getPrimaryGroup();
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).group.equalsIgnoreCase(primary)) return i;
        }
        for (net.luckperms.api.node.Node n : u.getNodes()) {
            if (n.getKey().startsWith("group.")) {
                String g = n.getKey().substring(6);
                for (int i = 0; i < ladder.size(); i++) {
                    if (ladder.get(i).group.equalsIgnoreCase(g)) return i;
                }
            }
        }
        return -1;
    }

    private void applyGroup(OfflinePlayer p, String add, String remove) {
        UserManager um = luckPerms.getUserManager();
        um.loadUser(p.getUniqueId()).thenAcceptAsync(u -> {
            if (remove != null) u.data().remove(Node.builder("group." + remove).build());
            if (add != null) u.data().add(Node.builder("group." + add).build());
            um.saveUser(u);
        });
    }

    /** Actualiza Discord: quita rol viejo, da rol nuevo y pone nick. Corre en async. */
    private void syncDiscord(String discordId, String mcName, Rank oldR, Rank newR) {
        if (jda == null || discordId == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) return;
                Member m = g.retrieveMemberById(discordId).complete();
                if (m == null) return;
                if (oldR != null && oldR.roleId != null && !oldR.roleId.isEmpty() && !oldR.roleId.equals("null")) {
                    Role ro = g.getRoleById(oldR.roleId);
                    if (ro != null) g.removeRoleFromMember(m, ro).queue();
                }
                if (newR.roleId != null && !newR.roleId.isEmpty() && !newR.roleId.equals("null")) {
                    Role rn = g.getRoleById(newR.roleId);
                    if (rn != null) g.addRoleToMember(m, rn).queue();
                }
                String nick = getConfig().getString("discord.nickname-format", "{rango} {mc}")
                        .replace("{rango}", newR.display).replace("{mc}", mcName);
                if (nick.length() > 32) nick = nick.substring(0, 32);
                m.modifyNickname(nick).queue(null, err -> {});
                User u = m.getUser();
                u.openPrivateChannel().queue(ch ->
                        ch.sendMessage("✅ Tu rango se actualizó a **" + newR.display + "** en "
                                + g.getName() + ".").queue(null, err2 -> {}));
            } catch (Exception e) {
                getLogger().warning("Error sincronizando Discord: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("stafflinkdiscord")) {
            if (!(s instanceof Player)) {
                s.sendMessage("Solo jugadores.");
                return true;
            }
            Player p = (Player) s;
            if (a.length < 1 || !a[0].matches("\\d{17,20}")) {
                p.sendMessage(msg("usage-link"));
                if (a.length >= 1) p.sendMessage(msg("bad-id"));
                return true;
            }
            links.set(p.getUniqueId() + ".discord", a[0]);
            links.set(p.getUniqueId() + ".mc", p.getName());
            saveLinks();
            p.sendMessage(msg("linked"));
            // Aviso por MD si el bot ya está conectado
            if (jda != null) {
                jda.retrieveUserById(a[0]).queue(u ->
                        u.openPrivateChannel().queue(ch ->
                                ch.sendMessage("🔗 Vinculado con **" + p.getName()
                                        + "** en el servidor.").queue(null, e -> {}), e -> {}));
            }
            return true;
        }

        if (name.equals("promote") || name.equals("demote")) {
            if (a.length < 1) {
                s.sendMessage("§eUso: /" + name + " <jugador>");
                return true;
            }
            OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
            if (t == null || (!t.hasPlayedBefore() && !t.isOnline())) {
                s.sendMessage("§cJugador no encontrado.");
                return true;
            }
            boolean up = name.equals("promote");
            luckPerms.getUserManager().loadUser(t.getUniqueId()).thenAcceptAsync(u -> {
                int cur = currentRank(u);
                if (cur < 0) {
                    s.sendMessage(msg("no-rank"));
                    return;
                }
                int next = up ? cur + 1 : cur - 1;
                if (next >= ladder.size()) {
                    s.sendMessage(msg("top-rank"));
                    return;
                }
                if (next < 0) {
                    s.sendMessage(msg("bottom-rank"));
                    return;
                }
                Rank oldR = ladder.get(cur);
                Rank newR = ladder.get(next);
                Bukkit.getScheduler().runTask(this, () -> {
                    applyGroup(t, newR.group, oldR.group);
                    String out = msg(up ? "promoted" : "demoted")
                            .replace("{jugador}", t.getName()).replace("{rango}", newR.display);
                    s.sendMessage(out);
                    String dcId = links.getString(t.getUniqueId() + ".discord");
                    if (dcId == null) {
                        if (t.isOnline()) ((Player) t).sendMessage(msg("need-link"));
                    } else {
                        syncDiscord(dcId, t.getName(), oldR, newR);
                    }
                });
            });
            return true;
        }
        return false;
    }

    /** ID de Discord vinculado a un UUID de MC (para otros plugins). */
    public String linkedDiscord(java.util.UUID uuid) {
        return links.getString(uuid + ".discord");
    }
}
