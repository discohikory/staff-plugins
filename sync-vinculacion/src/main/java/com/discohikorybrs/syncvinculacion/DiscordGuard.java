package com.discohikorybrs.syncvinculacion;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
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

import java.awt.Color;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2FA por Discord para staff (reemplazo del TOTP):
 * - Vincular: /stafflinkdiscord genera código por MD, se confirma en juego.
 * - Al entrar: IP nueva o sin vincular = freeze + MD "¿Eres tú? [Sí/No]".
 * - No o 3 fallos = kick + webhook + ping rol + quita rango (solo ahí).
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class DiscordGuard extends ListenerAdapter implements Listener {

    private final SyncVinculacion plugin;
    private final Map<UUID, String> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> linkExpiry = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkDc = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> frozen = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fails = new ConcurrentHashMap<>();
    private final Map<String, UUID> awaiting = new ConcurrentHashMap<>();

    DiscordGuard(SyncVinculacion plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("guard.enabled", true);
    }

    private String ipOf(Player p) {
        try {
            return p.getAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------- Vinculación con código ----------

    /** Paso 1: genera código y lo manda por MD. */
    void startLink(Player p, String dcId) {
        String code = String.format("%06d", (int) (Math.random() * 1000000));
        linkCodes.put(p.getUniqueId(), code);
        linkDc.put(p.getUniqueId(), dcId);
        linkExpiry.put(p.getUniqueId(), System.currentTimeMillis() + 300000L);
        p.sendMessage("§eRevisa tu MD de Discord: te envié un código. Escríbelo aquí.");
        if (plugin.jda() == null) {
            p.sendMessage("§cBot de Discord apagado. Avisa a un superior.");
            return;
        }
        plugin.jda().retrieveUserById(dcId).queue(u ->
                u.openPrivateChannel().queue(ch ->
                        ch.sendMessageEmbeds(new EmbedBuilder()
                                .setTitle("🔗 Código de vinculación")
                                .setDescription("Tu código para **" + p.getName() + "** es:\n\n# `" + code + "`\n\nEscríbelo en el juego. Caduca en 5 minutos.")
                                .setColor(new Color(0x2effa1)).build()).queue(null, e -> {}), e -> {}));
    }

    /** Paso 2: confirma el código escrito en el chat. Devuelve el DC ID si OK. */
    String confirmLink(Player p, String code) {
        String want = linkCodes.get(p.getUniqueId());
        Long exp = linkExpiry.get(p.getUniqueId());
        if (want == null || exp == null || System.currentTimeMillis() > exp) return null;
        if (!want.equals(code.trim())) return null;
        linkCodes.remove(p.getUniqueId());
        linkExpiry.remove(p.getUniqueId());
        String dc = linkDc.remove(p.getUniqueId());
        if (dc != null) plugin.finishLink(p, dc);
        return dc;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!enabled() || !plugin.isStaffRank(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            String dc = plugin.linkedDiscord(p.getUniqueId());
            String lastIp = plugin.lastIp(p.getUniqueId());
            String nowIp = ipOf(p);
            if (dc == null || !nowIp.equals(lastIp)) {
                freeze(p, dc == null
                        ? "§cVincula tu Discord: /stafflinkdiscord <tu-id>."
                        : "§eIP nueva detectada. Confirma en tu Discord.");
                if (dc != null) askConfirm(p, dc, nowIp);
            }
        }, 40L);
    }

    private void freeze(Player p, String reason) {
        frozen.put(p.getUniqueId(), true);
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        p.setAllowFlight(false);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                Integer.MAX_VALUE, 1, false, false, false));
        p.sendMessage(reason);
    }

    void unfreeze(Player p) {
        frozen.remove(p.getUniqueId());
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    boolean isFrozen(Player p) {
        return frozen.containsKey(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        frozen.remove(e.getPlayer().getUniqueId());
        linkCodes.remove(e.getPlayer().getUniqueId());
        linkDc.remove(e.getPlayer().getUniqueId());
        linkExpiry.remove(e.getPlayer().getUniqueId());
        fails.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isFrozen(e.getPlayer())) return;
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        // Confirmación de código de vínculo
        if (linkCodes.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            String code = e.getMessage().trim();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (confirmLink(p, code) == null) {
                    p.sendMessage("§cCódigo incorrecto o caducado. Pide otro con /stafflinkdiscord.");
                }
            });
            return;
        }
        if (!isFrozen(p)) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isFrozen(e.getPlayer())) return;
        String m = e.getMessage().toLowerCase().split(" ")[0];
        if (!m.equals("/stafflinkdiscord") && !m.equals("/login") && !m.equals("/register")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cEstás congelado: confirma en Discord o vincula tu cuenta.");
        }
    }

    // ---------- Confirmación Sí/No por Discord ----------

    private void askConfirm(Player p, String dcId, String ip) {
        if (plugin.jda() == null) return;
        String key = p.getUniqueId().toString();
        awaiting.put(key, p.getUniqueId());
        plugin.jda().retrieveUserById(dcId).queue(u ->
                u.openPrivateChannel().queue(ch ->
                        ch.sendMessageEmbeds(new EmbedBuilder()
                                .setTitle("🛡️ ¿Eres tú?")
                                .setDescription("**" + p.getName() + "** entrando desde IP nueva:\n`" + ip + "`")
                                .setColor(new Color(0xe8c15a)).build())
                                .setActionRow(
                                        Button.success("guard:yes:" + key, "Sí, soy yo"),
                                        Button.danger("guard:no:" + key, "No soy yo"))
                                .queue(null, e -> {}), e -> {}));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        if (!e.getComponentId().startsWith("guard:")) return;
        String[] parts = e.getComponentId().split(":");
        if (parts.length < 3) return;
        UUID id;
        try {
            id = UUID.fromString(parts[2]);
        } catch (Exception ex) {
            return;
        }
        // Solo el dueño de la cuenta confirma
        if (!e.getUser().getId().equals(discordOf(id))) {
            e.reply("⛔ Este botón no es para ti.").setEphemeral(true).queue();
            return;
        }
        Player p = Bukkit.getPlayer(id);
        if (parts[1].equals("yes")) {
            if (p != null && p.isOnline()) {
                plugin.rememberIp(id, ipOf(p));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    unfreeze(p);
                    p.sendMessage("§a¡Verificado! Bienvenido.");
                });
            }
            e.editMessageEmbeds(new EmbedBuilder()
                    .setTitle("✅ Acceso permitido")
                    .setColor(new Color(0x2effa1)).build()).setComponents().queue();
        } else {
            e.editMessageEmbeds(new EmbedBuilder()
                    .setTitle("⛔ Acceso denegado")
                    .setColor(new Color(0xff2d55)).build()).setComponents().queue();
            if (p != null && p.isOnline()) punish(p, "negó el acceso en Discord");
        }
        awaiting.remove(id.toString());
    }

    private String discordOf(UUID id) {
        try {
            net.luckperms.api.model.user.User u =
                    plugin.luckPerms().getUserManager().loadUser(id).get();
            return plugin.metaDiscord(u);
        } catch (Exception e) {
            return null;
        }
    }

    /** 3 fallos por chat (códigos) también castigan. */
    void fail(Player p, String why) {
        int n = fails.getOrDefault(p.getUniqueId(), 0) + 1;
        fails.put(p.getUniqueId(), n);
        if (n >= plugin.getConfig().getInt("guard.max-fails", 3)) {
            punish(p, why + " (" + n + " fallos)");
        } else {
            p.sendMessage("§cIntento " + n + "/3. " + why);
        }
    }

    private void punish(Player p, String reason) {
        fails.remove(p.getUniqueId());
        plugin.demoteToDefault(p.getUniqueId(), p.getName());
        plugin.alertWebhook(p.getName(), reason);
        Bukkit.getScheduler().runTask(plugin, () ->
                p.kickPlayer("§cVerificación fallida: " + reason));
    }
}
