package com.discohikorybrs.syncvinculacion;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
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
                        .addEventListeners(new RankListener())
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
            boolean up = name.equals("promote");
            String mc = a[0];
            String dcId = discordOf(mc);
            if (dcId == null) {
                s.sendMessage(msg("need-link-many").replace("{jugador}", mc));
                return true;
            }
            postRankPicker(s, mc, dcId, up);
            return true;
        }
        return false;
    }

    /** Publica el selector de rangos en #sincronizacion-discord. */
    private void postRankPicker(CommandSender s, String mc, String dcId, boolean up) {
        if (jda == null) {
            s.sendMessage("§cBot de Discord no conectado. Revisa el token.");
            return;
        }
        String execDc = (s instanceof Player) ? discordOf(s.getName()) : null;
        String guildId = getConfig().getString("discord.guild-id", "");
        String chId = getConfig().getString("discord.sync-channel-id", "");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Guild g = jda.getGuildById(guildId);
                if (g == null) {
                    sendSync(s, "§cServidor de Discord no encontrado en config.");
                    return;
                }
                TextChannel ch = g.getTextChannelById(chId);
                if (ch == null) {
                    sendSync(s, "§cCanal de sincronización no configurado.");
                    return;
                }
                net.dv8tion.jda.api.EmbedBuilder eb =
                        new net.dv8tion.jda.api.EmbedBuilder()
                        .setTitle((up ? "📈 Promotear a " : "📉 Demotear a ") + mc)
                        .setDescription("Solicitado por **"
                                + (s instanceof Player ? s.getName() : "consola")
                                + "**. Elige el rango destino:")
                        .setColor(up ? 0x2effa1 : 0xff2d55);
                StringSelectMenu.Builder menu = StringSelectMenu
                        .create("rankpick:" + (up ? "up" : "down") + ":" + mc + ":" + (execDc == null ? "-" : execDc))
                        .setPlaceholder("Selecciona el rango...")
                        .setRequiredRange(1, 1);
                for (int i = 0; i < ladder.size(); i++) {
                    Rank r = ladder.get(i);
                    menu.addOptions(SelectOption.of(r.display, String.valueOf(i))
                            .withDescription("Nivel " + (i + 1)));
                }
                ch.sendMessageEmbeds(eb.build())
                        .setComponents(ActionRow.of(menu.build())).queue(
                                ok -> sendSync(s, "§aSelector publicado en #sincronizacion-discord."),
                                err -> sendSync(s, "§cNo pude publicar: " + err.getMessage()));
            } catch (Exception e) {
                sendSync(s, "§cError: " + e.getMessage());
            }
        });
    }

    private void sendSync(CommandSender s, String m) {
        Bukkit.getScheduler().runTask(this, () -> s.sendMessage(m));
    }

    /** Aplica el rango elegido desde el selector de Discord. */
    private class RankListener extends ListenerAdapter {
        @Override
        public void onStringSelectInteraction(StringSelectInteractionEvent e) {
            if (!e.getComponentId().startsWith("rankpick:")) return;
            String[] p = e.getComponentId().split(":", 4);
            if (p.length < 4) return;
            boolean up = p[1].equals("up");
            String mc = p[2];
            String execDc = p[3];
            Member clicker = e.getMember();
            boolean allowed = clicker != null && (clicker.getId().equals(execDc)
                    || clicker.hasPermission(Permission.ADMINISTRATOR));
            if (!allowed) {
                e.reply("⛔ Solo quien pidió el cambio o un administrador.").setEphemeral(true).queue();
                return;
            }
            int idx;
            try {
                idx = Integer.parseInt(e.getValues().get(0));
            } catch (Exception ex) {
                return;
            }
            if (idx < 0 || idx >= ladder.size()) return;
            Rank newR = ladder.get(idx);
            Bukkit.getScheduler().runTask(SyncVinculacion.this, () -> {
                OfflinePlayer t = Bukkit.getOfflinePlayer(mc);
                // Quita todos los grupos de la escalera y pone el elegido
                UserManager um = luckPerms.getUserManager();
                um.loadUser(t.getUniqueId()).thenAcceptAsync(u -> {
                    for (Rank r : ladder) u.data().remove(Node.builder("group." + r.group).build());
                    u.data().add(Node.builder("group." + newR.group).build());
                    um.saveUser(u);
                });
                String dcId = discordOf(mc);
                syncDiscord(dcId, mc, null, newR);
                e.editMessageEmbeds(new net.dv8tion.jda.api.EmbedBuilder()
                        .setTitle((up ? "📈 Promoteado: " : "📉 Demoteado: ") + mc)
                        .setDescription("Nuevo rango: **" + newR.display + "**\nElegido por " + clicker.getAsMention())
                        .setColor(up ? 0x2effa1 : 0xff2d55).build())
                        .setComponents().queue();
            });
        }
    }

    /** ID de Discord vinculado a un UUID de MC (para otros plugins). */
    public String linkedDiscord(java.util.UUID uuid) {
        return links.getString(uuid + ".discord");
    }
}
