package com.discohikorybrs.syncvinculacion;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
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
        menu = new RankMenu(this);
        getServer().getPluginManager().registerEvents(menu, this);
        getLogger().info("SyncVinculacion v1.0.0 por Discohikorybrs activado.");
    }

    private RankMenu menu;

    /** Vista de la escalera para el menú. */
    public java.util.List<Rank> ladderView() {
        return ladder;
    }

    /** Aplica el rango elegido: LuckPerms + Discord + aviso en el canal sync. */
    public void applyRank(CommandSender executor, String mc, String dcId, int idx, boolean up) {
        if (idx < 0 || idx >= ladder.size()) return;
        Rank newR = ladder.get(idx);
        OfflinePlayer t = Bukkit.getOfflinePlayer(mc);
        UserManager um = luckPerms.getUserManager();
                um.loadUser(t.getUniqueId()).thenAcceptAsync(u -> {
                    for (Rank r : ladder) u.data().remove(Node.builder("group." + r.group).build());
                    u.data().add(Node.builder("group." + newR.group).build());
                    try {
                        u.setPrimaryGroup(newR.group);
                    } catch (Exception ignored) {}
                    um.saveUser(u);
                });
        syncDiscord(dcId, mc, null, newR);
        String out = msg(up ? "promoted" : "demoted")
                .replace("{jugador}", mc).replace("{rango}", newR.display);
        executor.sendMessage(out);
        logPromote(executor instanceof Player ? executor.getName() : "consola", mc, newR, up);
    }

    /** Publica el cambio en #sincronizacion-discord. */
    private void logPromote(String by, String mc, Rank newR, boolean up) {
        if (jda == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = getConfig().getString("discord.sync-channel-id", "");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) return;
                TextChannel ch = g.getTextChannelById(chId);
                if (ch == null) return;
                ch.sendMessageEmbeds(new net.dv8tion.jda.api.EmbedBuilder()
                        .setTitle((up ? "📈 Promoteado: " : "📉 Demoteado: ") + mc)
                        .setDescription("Nuevo rango: **" + newR.display + "**\nPor: **" + by + "**")
                        .setColor(up ? 0x2effa1 : 0xff2d55).build()).queue();
            } catch (Exception e) {
                getLogger().warning("No se pudo avisar en sync: " + e.getMessage());
            }
        });
    }

    /** Busca el Discord ID vinculado a un nick de MC (ignora mayúsculas). */
    private String discordOf(String mcName) {
        for (String key : links.getKeys(false)) {
            String mc = links.getString(key + ".mc");
            if (mc != null && mc.equalsIgnoreCase(mcName))
                return links.getString(key + ".discord");
        }
        return null;
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

    /** Embed de vinculación estilo premium: canal sync + MD, en español. */
    private void postLinkEmbed(String mc, String dcId) {
        if (jda == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = getConfig().getString("discord.sync-channel-id", "");
        java.util.List<String> pings = getConfig().getStringList("discord.link-ping-role-ids");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) return;
                Member m;
                try {
                    m = g.retrieveMemberById(dcId).complete();
                } catch (Exception e) {
                    return;
                }
                if (m == null) return;
                final Member member = m;
                // Rango actual en la escalera
                luckPerms.getUserManager().loadUser(Bukkit.getOfflinePlayer(mc).getUniqueId())
                        .thenAcceptAsync(u -> {
                            int cur = currentRank(u);
                            String rank = cur >= 0 ? ladder.get(cur).display : "Sin rango";
                            StringBuilder ping = new StringBuilder();
                            for (String roleId : pings) {
                                Role r = g.getRoleById(roleId);
                                if (r != null) ping.append(r.getAsMention()).append(" ");
                            }
                            String head = "https://minotar.net/helm/"
                                    + mc + "/100.png";
                            net.dv8tion.jda.api.EmbedBuilder eb =
                                    new net.dv8tion.jda.api.EmbedBuilder()
                                    .setTitle("🔗 Cuenta de Discord vinculada")
                                    .addField("Usuario de Minecraft", "`" + mc + "`", false)
                                    .addField("Usuario de Discord", member.getAsMention(), false)
                                    .addField("Rango Staff", "`" + rank + "`", false)
                                    .addField("La cuenta de Discord se vinculó correctamente.",
                                            "👤 Minecraft  💬 Discord  🛡️ Rango\n**"
                                            + mc + "**  " + member.getAsMention()
                                            + "  `" + rank + "`", false)
                                    .setThumbnail(head)
                                    .setFooter("SylenMC Network • Vinculación de cuentas")
                                    .setTimestamp(java.time.Instant.now())
                                    .setColor(0x2effa1);
                            TextChannel ch = g.getTextChannelById(chId);
                            if (ch != null) {
                                String pre = ping.toString().trim();
                                if (pre.isEmpty()) ch.sendMessageEmbeds(eb.build()).queue();
                                else ch.sendMessage(pre).setEmbeds(eb.build()).queue();
                            }
                            member.getUser().openPrivateChannel().queue(
                                    pc -> pc.sendMessageEmbeds(eb.build()).queue(null, e -> {}),
                                    e -> {});
                        });
            } catch (Exception e) {
                getLogger().warning("Embed de vínculo: " + e.getMessage());
            }
        });
    }
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
            postLinkEmbed(p.getName(), a[0]);
            return true;
        }

        if (name.equals("promote") || name.equals("demote")) {
            if (a.length < 1) {
                s.sendMessage("§eUso: /" + name + " <jugador>");
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("§cEste comando abre un menú: úsalo en el juego.");
                return true;
            }
            boolean up = name.equals("promote");
            String mc = a[0];
            String dcId = discordOf(mc);
            if (dcId == null) {
                s.sendMessage(msg("need-link-many").replace("{jugador}", mc));
                return true;
            }
            Player p = (Player) s;
            // Rango actual para marcarlo en el menú
            luckPerms.getUserManager().loadUser(Bukkit.getOfflinePlayer(mc).getUniqueId())
                    .thenAcceptAsync(u -> {
                        int c = currentRank(u);
                        Bukkit.getScheduler().runTask(this, () ->
                                menu.open(p, mc, dcId, up, c));
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
