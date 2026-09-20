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
 * Menú decorado con páginas para elegir el rango destino.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class RankMenu implements Listener {

    private final SyncVinculacion plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private static final int PER_PAGE = 7;
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private static class Pending {
        String mc, dcId;
        boolean up;
        boolean remove;
        int page;
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
        p.remove = false;
        p.page = 0;
        pending.put(executor.getUniqueId(), p);
        draw(executor, p, current);
    }

    /** Modo quitar: el menú elige qué rango RETIRAR. */
    public void openRemove(Player executor, String mc, String dcId, int current) {
        Pending p = new Pending();
        p.mc = mc;
        p.dcId = dcId;
        p.up = false;
        p.remove = true;
        p.page = 0;
        pending.put(executor.getUniqueId(), p);
        draw(executor, p, current);
    }

    /** Cabeza personalizada con el nombre del rango. */
    private ItemStack rankHead(SyncVinculacion.Rank r, int idx, int total, boolean current, boolean remove) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta m =
                (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        String mark = (!remove && current) ? " §e● actual" : "";
        m.setDisplayName("§f§l" + r.display + mark);
        m.setLore(Arrays.asList("§7Nivel " + (idx + 1) + "/" + total,
                remove ? "§cClic para QUITAR este rango" : (current ? "§cYa tiene este rango" : "§aClic para asignar")));
        head.setItemMeta(m);
        return head;
    }

    private void draw(Player executor, Pending pen, int current) {
        java.util.List<SyncVinculacion.Rank> ladder = plugin.ladderView();
        int pages = (int) Math.ceil(ladder.size() / (double) PER_PAGE);
        String head = pen.remove ? "§c§lQuitar rango a " : (pen.up ? "§a§lPromotear a " : "§c§lDemotear a ");
        Inventory inv = Bukkit.createInventory(executor, 27,
                head + pen.mc + " §8(" + (pen.page + 1) + "/" + pages + ")");
        // Marco decorado
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);
        for (int s = 0; s < 27; s++) inv.setItem(s, glass);
        // Rangos de la página (cabezas personalizadas)
        int start = pen.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= ladder.size()) break;
            SyncVinculacion.Rank r = ladder.get(idx);
            inv.setItem(SLOTS[i], rankHead(r, idx, ladder.size(), idx == current, pen.remove));
        }
        // Anterior
        if (pen.page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta pm = prev.getItemMeta();
            pm.setDisplayName("§e§l← Anterior");
            prev.setItemMeta(pm);
            inv.setItem(18, prev);
        }
        // Info centro
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§e" + pen.mc);
        im.setLore(Arrays.asList("§7Discord vinculado: §aSí",
                "§7Página " + (pen.page + 1) + " de " + pages));
        info.setItemMeta(im);
        inv.setItem(22, info);
        // Siguiente
        if (pen.page < pages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nm = next.getItemMeta();
            nm.setDisplayName("§e§lSiguiente →");
            next.setItemMeta(nm);
            inv.setItem(26, next);
        }
        // Cerrar
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName("§c§lCerrar");
        close.setItemMeta(cm);
        inv.setItem(24, close);
        executor.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        String title = e.getView().getTitle();
        if (!title.startsWith("§a§lPromotear a ") && !title.startsWith("§c§lDemotear a ")
                && !title.startsWith("§c§lQuitar rango a ")) return;
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
        int pages = (int) Math.ceil(ladder.size() / (double) PER_PAGE);
        if (name.contains("Siguiente") && pen.page < pages - 1) {
            pen.page++;
            draw(p, pen, currentOf(pen.mc));
            return;
        }
        if (name.contains("Anterior") && pen.page > 0) {
            pen.page--;
            draw(p, pen, currentOf(pen.mc));
            return;
        }
        if (name.contains("Cerrar")) {
            pending.remove(p.getUniqueId());
            p.closeInventory();
            return;
        }
        for (int i = 0; i < ladder.size(); i++) {
            if (name.contains(ladder.get(i).display)) {
                if (!pen.remove && name.contains("● actual")) {
                    p.sendMessage("§cYa tiene ese rango.");
                    return;
                }
                final int idx = i;
                p.closeInventory();
                pending.remove(p.getUniqueId());
                if (pen.remove) plugin.applyRemove(p, pen.mc, pen.dcId, idx);
                else plugin.applyRank(p, pen.mc, pen.dcId, idx, pen.up);
                return;
            }
        }
    }

    private int currentOf(String mc) {
        try {
            org.bukkit.OfflinePlayer t = Bukkit.getOfflinePlayer(mc);
            net.luckperms.api.LuckPerms lp =
                    net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User u =
                    lp.getUserManager().getUser(t.getUniqueId());
            if (u == null) return -1;
            String primary = u.getPrimaryGroup();
            java.util.List<SyncVinculacion.Rank> ladder = plugin.ladderView();
            for (int i = 0; i < ladder.size(); i++) {
                if (ladder.get(i).group.equalsIgnoreCase(primary)) return i;
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
