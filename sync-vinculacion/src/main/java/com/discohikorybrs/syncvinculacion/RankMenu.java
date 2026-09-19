package com.discohikorybrs.syncvinculacion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Menú interactivo en juego para elegir el rango destino.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class RankMenu implements Listener {

    private final SyncVinculacion plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    private static class Pending {
        String mc, dcId;
        boolean up;
    }

    private static final Material[] WOOLS = {
            Material.LIME_WOOL, Material.GREEN_WOOL, Material.LIGHT_GRAY_WOOL,
            Material.CYAN_WOOL, Material.LIGHT_BLUE_WOOL, Material.BLUE_WOOL,
            Material.PURPLE_WOOL, Material.MAGENTA_WOOL, Material.PINK_WOOL,
            Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.RED_WOOL,
            Material.BROWN_WOOL, Material.GRAY_WOOL
    };

    public RankMenu(SyncVinculacion plugin) {
        this.plugin = plugin;
    }

    public void open(Player executor, String mc, String dcId, boolean up, int current) {
        Pending p = new Pending();
        p.mc = mc;
        p.dcId = dcId;
        p.up = up;
        pending.put(executor.getUniqueId(), p);
        Inventory inv = Bukkit.createInventory(executor, 27,
                (up ? "§a§lPromotear a " : "§c§lDemotear a ") + mc);
        // Decoración bordes
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);
        for (int s : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26})
            inv.setItem(s, glass);
        // Rangos al centro
        java.util.List<SyncVinculacion.Rank> ladder = plugin.ladderView();
        int slot = 10;
        for (int i = 0; i < ladder.size() && slot <= 16; i++) {
            if (slot == 17) slot = 19;
            SyncVinculacion.Rank r = ladder.get(i);
            ItemStack w = new ItemStack(WOOLS[i % WOOLS.length]);
            ItemMeta m = w.getItemMeta();
            String mark = (i == current) ? " §e● actual" : "";
            m.setDisplayName("§f§l" + r.display + mark);
            m.setLore(Arrays.asList("§7Nivel " + (i + 1) + "/" + ladder.size(),
                    i == current ? "§cYa tiene este rango" : "§aClic para asignar"));
            w.setItemMeta(m);
            inv.setItem(slot++, w);
        }
        // Info abajo
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§e" + mc);
        im.setLore(Arrays.asList("§7Discord vinculado: §aSí", "§7Elige el rango destino"));
        info.setItemMeta(im);
        inv.setItem(22, info);
        executor.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        String title = e.getView().getTitle();
        if (!title.startsWith("§a§lPromotear a ") && !title.startsWith("§c§lDemotear a ")) return;
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        Pending pen = pending.get(p.getUniqueId());
        if (pen == null) {
            p.closeInventory();
            return;
        }
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().hasDisplayName()) return;
        String name = it.getItemMeta().getDisplayName();
        java.util.List<SyncVinculacion.Rank> ladder = plugin.ladderView();
        for (int i = 0; i < ladder.size(); i++) {
            if (name.contains(ladder.get(i).display)) {
                if (name.contains("● actual")) {
                    p.sendMessage("§cYa tiene ese rango.");
                    return;
                }
                final int idx = i;
                p.closeInventory();
                pending.remove(p.getUniqueId());
                plugin.applyRank(p, pen.mc, pen.dcId, idx, pen.up);
                return;
            }
        }
    }
}
