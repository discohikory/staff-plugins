package com.discohikorybrs.authstaff;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teclado 2FA estilo premium: cabezas numeradas reales, progreso,
 * borrar, atrás y verificar. Título SylenMC en español.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class CodeMenu implements Listener {

    private final AuthStaff plugin;
    private final Map<UUID, StringBuilder> codes = new HashMap<>();

    private static final int[] PROGRESS = {10, 11, 12, 14, 15, 16};
    private static final int[] DIGIT_SLOTS = {21, 22, 23, 30, 31, 32, 39, 40, 41};
    private static final int SLOT_ZERO = 49;
    private static final int SLOT_CLEAR = 13;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_VERIFY = 50;
    private static final int SLOT_STATUS = 4;

    // Hashes de texturas de cabezas 0-9 (formato minecraft textures).
    private static final String[] DIGIT_HASH = {
            "3f09018f46f349e553446996a38649fcfcf9fdfd62916aec33ebca96bb21b5",
            "ca516fbae16058f251aef9a68d3078549f48f6d5b683f19cf5a1745217d72cc",
            "4698add39cf9e4ea92d42fadefdec3be8a7dafa11fb359de752e9f54aecedc9a",
            "fd9e4cd5e1b9f3c8d6ca5a1bf45d86edd1d51e535dbf855fe9d2f5d4cffcd2",
            "f2a3d53898141c58d5acbcfc87469a87d48c5c1fc82fb4e72f7015a3648058",
            "d1fe36c4104247c87ebfd358ae6ca7809b61affd6245fa984069275d1cba763",
            "3ab4da2358b7b0e8980d03bdb64399efb4418763aaf89afb0434535637f0a1",
            "297712ba32496c9e82b20cc7d16e168b035b6f89f3df014324e4d7c365db3fb",
            "abc0fda9fa1d9847a3b146454ad6737ad1be48bda94324426eca0918512d",
            "d6abc61dcaefbd52d9689c0697c24c7ec4bc1afb56b8b3755e6154b24a5d8ba"
    };

    public CodeMenu(AuthStaff plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        codes.computeIfAbsent(player.getUniqueId(), k -> new StringBuilder());
        Inventory inv = Bukkit.createInventory(player, 54, "§6§lSylenMC Security §8• §f2FA");
        refresh(inv, player);
        player.openInventory(inv);
    }

    private static String textureBase64(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                + hash + "\"}}}";
        return java.util.Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String textureFor(int digit) {
        try {
            List<String> list = plugin.getConfig().getStringList("digit-heads");
            if (digit >= 0 && digit < list.size() && !list.get(digit).trim().isEmpty())
                return list.get(digit).trim();
        } catch (Exception ignored) {}
        return textureBase64(DIGIT_HASH[digit]);
    }

    private ItemStack digitHead(int digit) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setDisplayName("§d§l" + digit);
        meta.setLore(Arrays.asList("§7Clic para marcar el " + digit));
        applyTexture(meta, textureFor(digit));
        head.setItemMeta(meta);
        return head;
    }

    /** Aplica textura Base64 a la cabeza vía GameProfile (sin dependencias). */
    private void applyTexture(SkullMeta meta, String base64) {
        try {
            Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> prop = Class.forName("com.mojang.authlib.properties.Property");
            Object profile = gp.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), "digit");
            Object props = gp.getMethod("getProperties").invoke(profile);
            Object p = prop.getConstructor(String.class, String.class)
                    .newInstance("textures", base64);
            props.getClass().getMethod("put", Object.class, Object.class)
                    .invoke(props, "textures", p);
            meta.getClass().getMethod("setProfile", gp).invoke(meta, profile);
        } catch (Exception ignored) {
        }
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        it.setItemMeta(meta);
        return it;
    }

    private void refresh(Inventory inv, Player p) {
        String code = codes.getOrDefault(p.getUniqueId(), new StringBuilder()).toString();
        // Marco negro
        ItemStack frame = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s : new int[]{0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53})
            inv.setItem(s, frame);
        // Acentos morados
        ItemStack accent = named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int s : new int[]{22, 31, 40}) inv.setItem(s, accent);
        // Estado
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < 6; i++) shown.append(i < code.length() ? code.charAt(i) : "•");
        inv.setItem(SLOT_STATUS, named(Material.AMETHYST_SHARD, "§d§lCÓDIGO AUTHENTICATOR",
                "§7Pon el código de 6 dígitos", "§7de tu app de autenticación.", "",
                "§fIngresado: §d" + shown + " §8(" + code.length() + "/6)"));
        // Progreso: rojo -> verde por dígito ingresado
        for (int i = 0; i < 6; i++) {
            inv.setItem(PROGRESS[i], named(i < code.length()
                    ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, " "));
        }
        // Dígitos 1-9 y 0
        for (int d = 1; d <= 9; d++) inv.setItem(DIGIT_SLOTS[d - 1], digitHead(d));
        inv.setItem(SLOT_ZERO, digitHead(0));
        // Botones
        inv.setItem(SLOT_CLEAR, named(Material.RED_CONCRETE, "§c§lBORRAR", "§7Borra todos los dígitos."));
        inv.setItem(SLOT_BACK, named(Material.YELLOW_CONCRETE, "§e§lATRÁS", "§7Borra el último dígito."));
        inv.setItem(SLOT_VERIFY, named(Material.LIME_CONCRETE, "§a§lVERIFICAR",
                "§7Clic cuando estén", "§7los seis dígitos."));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!e.getView().getTitle().equals("§6§lSylenMC Security §8• §f2FA")) return;
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        StringBuilder code = codes.computeIfAbsent(p.getUniqueId(), k -> new StringBuilder());
        int slot = e.getRawSlot();
        // Dígitos
        for (int d = 1; d <= 9; d++) {
            if (slot == DIGIT_SLOTS[d - 1]) {
                if (code.length() < 6) {
                    code.append(d);
                    p.playSound(p.getLocation(),
                            org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                }
                refresh(e.getInventory(), p);
                return;
            }
        }
        if (slot == SLOT_ZERO) {
            if (code.length() < 6) {
                code.append('0');
                p.playSound(p.getLocation(),
                        org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
            refresh(e.getInventory(), p);
            return;
        }
        if (slot == SLOT_CLEAR) {
            code.setLength(0);
            refresh(e.getInventory(), p);
            return;
        }
        if (slot == SLOT_BACK) {
            if (code.length() > 0) code.setLength(code.length() - 1);
            refresh(e.getInventory(), p);
            return;
        }
        if (slot == SLOT_VERIFY) {
            if (code.length() != 6) {
                p.sendMessage("§cPon los 6 dígitos primero.");
                return;
            }
            String c = code.toString();
            code.setLength(0);
            p.closeInventory();
            plugin.tryCode(p, c);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // Se conserva el código por si reabre el menú
    }

    public void clear(UUID uuid) {
        codes.remove(uuid);
    }
}
