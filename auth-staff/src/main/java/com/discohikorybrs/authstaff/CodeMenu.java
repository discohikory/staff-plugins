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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menú interactivo de 6 dígitos con CABEZAS numeradas 0-9.
 * Clic izquierdo +1, clic derecho -1, confirma con el bloque verde.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class CodeMenu implements Listener {

    private final AuthStaff plugin;
    private final Map<UUID, int[]> digits = new ConcurrentHashMap<>();
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15};
    private static final int CONFIRM = 22;
    private static final int INFO = 4;

    public CodeMenu(AuthStaff plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        int[] d = digits.computeIfAbsent(player.getUniqueId(), k -> new int[6]);
        Inventory inv = Bukkit.createInventory(player, 27, "§6§lCódigo 2FA — AuthStaff");
        refresh(inv, d);
        player.openInventory(inv);
    }

    private ItemStack headFor(int digitPos, int value) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setDisplayName("§f§lDígito " + (digitPos + 1) + ": §6§l" + value);
        meta.setLore(Arrays.asList("§7Izquierdo: §a+1", "§7Derecho: §c-1"));
        String tex = textureFor(value);
        if (tex != null && !tex.isEmpty()) applyTexture(meta, tex);
        head.setItemMeta(meta);
        return head;
    }

    private String textureFor(int digit) {
        try {
            List<String> list = plugin.getConfig().getStringList("digit-heads");
            if (digit >= 0 && digit < list.size()) return list.get(digit).trim();
        } catch (Exception ignored) {}
        return null;
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
            // Sin textura: queda la cabeza normal con el número en el nombre
        }
    }

    private void refresh(Inventory inv, int[] d) {
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§ePon los 6 dígitos de tu app");
        im.setLore(Arrays.asList("§7Clic izquierdo: §a+1", "§7Clic derecho: §c-1", "§7Confirma con el bloque verde"));
        info.setItemMeta(im);
        inv.setItem(INFO, info);
        for (int i = 0; i < 6; i++) inv.setItem(SLOTS[i], headFor(i, d[i]));
        ItemStack ok = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta om = ok.getItemMeta();
        om.setDisplayName("§a§lCONFIRMAR");
        ok.setItemMeta(om);
        inv.setItem(CONFIRM, ok);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!e.getView().getTitle().equals("§6§lCódigo 2FA — AuthStaff")) return;
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        int[] d = digits.computeIfAbsent(p.getUniqueId(), k -> new int[6]);
        int slot = e.getRawSlot();
        int idx = -1;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) idx = i;
        if (idx >= 0) {
            if (e.isRightClick()) d[idx] = (d[idx] + 9) % 10;
            else d[idx] = (d[idx] + 1) % 10;
            refresh(e.getInventory(), d);
            return;
        }
        if (slot == CONFIRM) {
            StringBuilder sb = new StringBuilder();
            for (int v : d) sb.append(v);
            p.closeInventory();
            plugin.tryCode(p, sb.toString());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // Se conservan los dígitos por si reabre el menú
    }

    public void clear(UUID uuid) {
        digits.remove(uuid);
    }
}
