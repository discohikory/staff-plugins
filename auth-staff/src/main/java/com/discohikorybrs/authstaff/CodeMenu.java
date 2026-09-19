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

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menú interactivo de 6 dígitos: clic izq +1, clic der -1, confirmar con lana verde.
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

    private void refresh(Inventory inv, int[] d) {
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§ePon los 6 dígitos de tu app");
        im.setLore(Arrays.asList("§7Clic izquierdo: §a+1", "§7Clic derecho: §c-1", "§7Confirma con la lana verde"));
        info.setItemMeta(im);
        inv.setItem(INFO, info);
        for (int i = 0; i < 6; i++) {
            ItemStack w = new ItemStack(Material.valueOf(digitColor(d[i])));
            ItemMeta m = w.getItemMeta();
            m.setDisplayName("§f§lDígito " + (i + 1) + ": §6§l" + d[i]);
            w.setItemMeta(m);
            inv.setItem(SLOTS[i], w);
        }
        ItemStack ok = new ItemStack(Material.LIME_WOOL);
        ItemMeta om = ok.getItemMeta();
        om.setDisplayName("§a§lCONFIRMAR");
        ok.setItemMeta(om);
        inv.setItem(CONFIRM, ok);
    }

    private String digitColor(int d) {
        String[] mats = {"WHITE_WOOL", "ORANGE_WOOL", "MAGENTA_WOOL", "LIGHT_BLUE_WOOL",
                "YELLOW_WOOL", "LIME_WOOL", "PINK_WOOL", "GRAY_WOOL", "CYAN_WOOL", "PURPLE_WOOL"};
        return mats[d % 10];
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
