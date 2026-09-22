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
import net.luckperms.api.node.types.MetaNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.File;
import java.io.IOException;

/**
 * Vincula MC↔Discord y sincroniza /promote y /demote con LuckPerms + Discord.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class SyncVinculacion extends JavaPlugin implements CommandExecutor {

    private LuckPerms luckPerms;
    private JDA jda;

    public static class Rank {
        String group, display, roleId;
    }

    private final List<Rank> ladder = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLadder();
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
        getServer().getPluginManager().registerEvents(new JoinCheck(this), this);
        guard = new DiscordGuard(this);
        getServer().getPluginManager().registerEvents(guard, this);
        if (jda != null) {
            try {
                jda.addEventListener(guard);
            } catch (Exception ignored) {}
        }
        ipsFile = new File(getDataFolder(), "ips.yml");
        ips = YamlConfiguration.loadConfiguration(ipsFile);
        migrateLinks();
        // Cada 5 min: si un vinculado ya no tiene rango staff, se desvincula solo
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::autoUnlinkCheck, 6000L, 6000L);
        getLogger().info("SyncVinculacion v1.0.0 por Discohikorybrs activado.");
    }

    /** Migra links.yml viejo al meta de LuckPerms (una vez). */
    private void migrateLinks() {
        try {
            java.io.File f = new java.io.File(getDataFolder(), "links.yml");
            if (!f.exists()) return;
            org.bukkit.configuration.file.FileConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            int n = 0;
            for (String key : y.getKeys(false)) {
                String dc = y.getString(key + ".discord");
                if (dc == null || dc.isEmpty()) continue;
                try {
                    UUID id = UUID.fromString(key);
                    UserManager um = luckPerms.getUserManager();
                    final String fdc = dc;
                    um.loadUser(id).thenAcceptAsync(u -> {
                        u.data().add(MetaNode.builder("discord-id", fdc).build());
                        um.saveUser(u);
                    });
                    n++;
                } catch (Exception ignored) {}
            }
            java.io.File bak = new java.io.File(getDataFolder(), "links.yml.migrated");
            if (!f.renameTo(bak)) f.delete();
            getLogger().info("Migrados " + n + " vínculos a LuckPerms.");
        } catch (Exception e) {
            getLogger().warning("Migración links: " + e.getMessage());
        }
    }

    private RankMenu menu;
    private DiscordGuard guard;
    private FileConfiguration ips;
    private File ipsFile;

    public JDA jda() {
        return jda;
    }

    public LuckPerms luckPerms() {
        return luckPerms;
    }

    /** ¿Tiene rango de la escalera? (OP no cuenta). */
    public boolean isStaffRank(UUID uuid) {
        try {
            net.luckperms.api.model.user.User u =
                    luckPerms.getUserManager().getUser(uuid);
            if (u == null) {
                u = luckPerms.getUserManager().loadUser(uuid).get();
            }
            return u != null && hasLadderRank(u);
        } catch (Exception e) {
            return false;
        }
    }

    public String lastIp(UUID uuid) {
        try {
            return ips.getString(uuid.toString() + ".ip");
        } catch (Exception e) {
            return null;
        }
    }

    public void rememberIp(UUID uuid, String ip) {
        try {
            ips.set(uuid.toString() + ".ip", ip);
            ips.save(ipsFile);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar ips.yml");
        }
    }

    /** Quita todos los rangos staff y deja en default (solo tras fallar verificación). */
    public void demoteToDefault(UUID uuid, String mc) {
        UserManager um = luckPerms.getUserManager();
        um.loadUser(uuid).thenAcceptAsync(u -> {
            for (Rank r : ladder) u.data().remove(Node.builder("group." + r.group).build());
            try {
                u.setPrimaryGroup("default");
            } catch (Exception ignored) {}
            um.saveUser(u);
        });
        getLogger().warning("DEMOTE seguridad: " + mc + " sin rango staff.");
    }

    /** Alerta al webhook de fallos + ping al rol. */
    public void alertWebhook(String mc, String reason) {
        String url = getConfig().getString("guard.webhook-failed", "");
        if (url == null || url.isEmpty()) {
            getLogger().warning("ALERTA " + mc + ": " + reason + " (sin webhook)");
            return;
        }
        String role = getConfig().getString("guard.ping-role-id", "");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String content = (role == null || role.isEmpty()) ? "" : "<@&" + role + ">";
                String json = "{\"content\":\"" + content.replace("\"", "") + "\",\"embeds\":[{"
                        + "\"title\":\"Alerta 2FA: " + mc.replace("\"", "") + "\","
                        + "\"description\":\"" + reason.replace("\"", "") + "\","
                        + "\"color\":15548997}]}";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.getOutputStream().write(json.getBytes("UTF-8"));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                getLogger().warning("Webhook: " + e.getMessage());
            }
        });
    }

    /** Vista de la escalera para el menú. */
    public java.util.List<Rank> ladderView() {
        return ladder;
    }

    /** Quita un rango: LuckPerms + rol Discord + nick + aviso. */
    public void applyRemove(CommandSender executor, String mc, String dcId, int idx) {
        if (idx < 0 || idx >= ladder.size()) return;
        Rank gone = ladder.get(idx);
        OfflinePlayer t = Bukkit.getOfflinePlayer(mc);
        UserManager um = luckPerms.getUserManager();
        um.loadUser(t.getUniqueId()).thenAcceptAsync(u -> {
            u.data().remove(Node.builder("group." + gone.group).build());
            um.saveUser(u);
        });
        removeDiscordRole(dcId, mc, gone);
        String out = msg("removed").replace("{jugador}", mc).replace("{rango}", gone.display);
        executor.sendMessage(out);
        logRemove(executor instanceof Player ? executor.getName() : "consola", mc, gone);
    }

    private void removeDiscordRole(String dcId, String mc, Rank gone) {
        if (jda == null || dcId == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
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
                if (gone.roleId != null && !gone.roleId.isEmpty() && !gone.roleId.equals("null")) {
                    Role ro = g.getRoleById(gone.roleId);
                    if (ro != null) {
                        try {
                            g.removeRoleFromMember(m, ro).complete();
                        } catch (Exception ignored) {}
                    }
                }
                // Si no le quedan roles staff, nick vuelve al nick de MC
                boolean hasStaff = false;
                for (Role r : m.getRoles()) {
                    for (Rank x : ladder) {
                        if (x.roleId != null && x.roleId.equals(r.getId())) {
                            hasStaff = true;
                            break;
                        }
                    }
                    if (hasStaff) break;
                }
                if (!hasStaff) {
                    try {
                        m.modifyNickname(mc).queue(null, e -> {});
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                getLogger().warning("Quitar rol Discord: " + e.getMessage());
            }
        });
    }

    private void logRemove(String by, String mc, Rank gone) {
        if (jda == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = chanFor("ranks");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) return;
                TextChannel ch = g.getTextChannelById(chId);
                if (ch == null) return;
                ch.sendMessageEmbeds(new net.dv8tion.jda.api.EmbedBuilder()
                        .setTitle("📉 Rango retirado: " + mc)
                        .setDescription("Rango quitado: **" + gone.display + "**\nPor: **" + by + "**")
                        .setColor(0xff2d55).build()).queue();
            } catch (Exception e) {
                getLogger().warning("Aviso quitar rango: " + e.getMessage());
            }
        });
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

    /** Canal para cada tipo de mensaje (links/ranks/unlinks), con fallback. */
    private String chanFor(String kind) {
        String c = getConfig().getString("discord.channels." + kind, "");
        if (c == null || c.isEmpty())
            c = getConfig().getString("discord.sync-channel-id", "");
        return c;
    }

    /** Publica el cambio en #sincronizacion-discord. */
    private void logPromote(String by, String mc, Rank newR, boolean up) {
        if (jda == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = chanFor("ranks");
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

    /** Discord ID desde el meta de LuckPerms (compartido entre servidores). */
    String metaDiscord(net.luckperms.api.model.user.User u) {
        try {
            String v = u.getCachedData().getMetaData().getMetaValue("discord-id");
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private void metaSet(UUID uuid, String dcId) {
        UserManager um = luckPerms.getUserManager();
        um.loadUser(uuid).thenAcceptAsync(u -> {
            for (Node n : new java.util.ArrayList<>(u.getNodes())) {
                if (n instanceof MetaNode
                        && ((MetaNode) n).getMetaKey().equals("discord-id")) {
                    u.data().remove(n);
                }
            }
            if (dcId != null) u.data().add(MetaNode.builder("discord-id", dcId).build());
            um.saveUser(u);
        });
    }

    /** ¿Tiene algún rango de la escalera? */
    private boolean hasLadderRank(net.luckperms.api.model.user.User u) {
        for (net.luckperms.api.node.Node n : u.getNodes()) {
            if (!n.getKey().startsWith("group.")) continue;
            String gname = n.getKey().substring(6);
            for (Rank r : ladder) {
                if (r.group.equalsIgnoreCase(gname)) return true;
            }
        }
        return false;
    }

    /** Revisa UN jugador online: sin rango staff + vinculado = desvincular. */
    void checkPlayer(Player p) {
        UserManager um = luckPerms.getUserManager();
        um.loadUser(p.getUniqueId()).thenAcceptAsync(u -> {
            String dc = metaDiscord(u);
            if (dc == null || hasLadderRank(u)) return;
            Bukkit.getScheduler().runTask(this, () ->
                    unlink(u.getUniqueId(), p.getName(), dc, "sin rango staff"));
        });
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

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
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

    /** Completa un vínculo confirmado con código. */
    public void finishLink(Player p, String dcId) {
        metaSet(p.getUniqueId(), dcId);
        p.sendMessage(msg("linked"));
        postLinkEmbed(p.getName(), dcId);
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
        UserManager um = luckPerms.getUserManager();
        if (name.equals("stafflinkdiscord")) {
            // /stafflinkdiscord remove <mc> — solo superiores (sync.admin)
            if (a.length >= 1 && a[0].equalsIgnoreCase("remove")) {
                if (!s.hasPermission("sync.admin")) {
                    s.sendMessage("§cSolo superiores al Mánager.");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage("§eUso: /stafflinkdiscord remove <nick-mc>");
                    return true;
                }
                final String target = a[1];
                um.lookupUniqueId(target).thenAcceptAsync(id -> {
                    if (id == null) {
                        sendSync(s, msg("not-linked").replace("{jugador}", target));
                        return;
                    }
                    um.loadUser(id).thenAcceptAsync(u -> {
                        String dc = metaDiscord(u);
                        if (dc == null) {
                            sendSync(s, msg("not-linked").replace("{jugador}", target));
                            return;
                        }
                        Bukkit.getScheduler().runTask(this, () -> {
                            unlink(id, target, dc, "desvinculado por un superior");
                            s.sendMessage(msg("unlinked").replace("{jugador}", target));
                        });
                    });
                });
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Solo jugadores (o usa: /stafflinkdiscord remove <nick>).");
                return true;
            }
            Player p = (Player) s;
            if (a.length < 1 || !a[0].matches("\\d{17,20}")) {
                p.sendMessage(msg("usage-link"));
                if (a.length >= 1) p.sendMessage(msg("bad-id"));
                return true;
            }
            final String id = a[0];
            um.loadUser(p.getUniqueId()).thenAcceptAsync(u -> {
                String prev = metaDiscord(u);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (prev != null && prev.equals(id)) {
                        // Ya vinculado: aviso por MD
                        p.sendMessage(msg("already-linked"));
                        if (jda != null) {
                            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                                try {
                                    jda.retrieveUserById(id).queue(us ->
                                            us.openPrivateChannel().queue(ch ->
                                                    ch.sendMessage("ℹ️ Tu cuenta **" + p.getName()
                                                            + "** ya está vinculada. Si no fuiste tú, avisa a un superior.")
                                                            .queue(null, e -> {}), e -> {}));
                                } catch (Exception ignored) {}
                            });
                        }
                        return;
                    }
                    // Nuevo vínculo: código por MD para confirmar en juego
                    guard.startLink(p, id);
                });
            });
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
            Player p = (Player) s;
            um.lookupUniqueId(mc).thenAcceptAsync(id -> {
                if (id == null) {
                    sendSync(s, "§cJugador no encontrado (debe haber entrado al menos una vez).");
                    return;
                }
                um.loadUser(id).thenAcceptAsync(u -> {
                    String dcId = metaDiscord(u); // null = sin vincular: solo MC
                    int c = currentRank(u);
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (up) menu.open(p, mc, dcId, true, c);
                        else menu.openRemove(p, mc, dcId, c);
                    });
                });
            });
            return true;
        }
        return false;
    }

    private void sendSync(CommandSender s, String m) {
        Bukkit.getScheduler().runTask(this, () -> s.sendMessage(m));
    }

    /** Desvincula: borra meta, quita roles staff en Discord y avisa. */
    public void unlink(UUID uuid, String mc, String dcId, String reason) {
        metaSet(uuid, null);
        if (dcId == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda == null ? null : jda.getGuildById(guildId);
                if (g == null) return;
                Member m;
                try {
                    m = g.retrieveMemberById(dcId).complete();
                } catch (Exception e) {
                    return;
                }
                if (m == null) return;
                for (Rank r : ladder) {
                    if (r.roleId == null || r.roleId.isEmpty() || r.roleId.equals("null")) continue;
                    Role ro = g.getRoleById(r.roleId);
                    if (ro != null) {
                        try {
                            g.removeRoleFromMember(m, ro).complete();
                        } catch (Exception ignored) {}
                    }
                }
                try {
                    m.modifyNickname(mc).queue(null, e -> {});
                } catch (Exception ignored) {}
                m.getUser().openPrivateChannel().queue(
                        ch -> ch.sendMessage("🔓 Tu cuenta **" + mc + "** fue desvinculada ("
                                + reason + ").").queue(null, e -> {}), e -> {});
                logUnlink(mc, reason);
            } catch (Exception e) {
                getLogger().warning("Desvincular: " + e.getMessage());
            }
        });
    }

    private void logUnlink(String mc, String reason) {
        if (jda == null) return;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = chanFor("unlinks");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) return;
                TextChannel ch = g.getTextChannelById(chId);
                if (ch == null) return;
                ch.sendMessageEmbeds(new net.dv8tion.jda.api.EmbedBuilder()
                        .setTitle("🔓 Cuenta desvinculada: " + mc)
                        .setDescription("Motivo: **" + reason + "**")
                        .setColor(0xff2d55).build()).queue();
            } catch (Exception e) {
                getLogger().warning("Aviso desvinculación: " + e.getMessage());
            }
        });
    }

    /** Revisa vinculados sin rango staff y los desvincula (online + al entrar). */
    private void autoUnlinkCheck() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) checkPlayer(p);
        } catch (Exception e) {
            getLogger().warning("Auto-desvincular: " + e.getMessage());
        }
    }

    /** ID de Discord vinculado a un UUID de MC (para otros plugins). */
    public String linkedDiscord(UUID uuid) {
        try {
            net.luckperms.api.model.user.User u =
                    luckPerms.getUserManager().loadUser(uuid).get();
            return metaDiscord(u);
        } catch (Exception e) {
            return null;
        }
    }
}
