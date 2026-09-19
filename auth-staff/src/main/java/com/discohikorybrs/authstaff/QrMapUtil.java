package com.discohikorybrs.authstaff;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

/**
 * Genera el mapa con el QR del TOTP y lo entrega al jugador.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public final class QrMapUtil {

    private QrMapUtil() {}

    public static boolean[][] qrMatrix(String text) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 128, 128, hints);
        boolean[][] out = new boolean[128][128];
        for (int x = 0; x < 128; x++)
            for (int y = 0; y < 128; y++)
                out[x][y] = m.get(x, y);
        return out;
    }

    public static class QrRenderer extends MapRenderer {
        private final boolean[][] matrix;
        private boolean done;

        public QrRenderer(boolean[][] matrix) {
            super(true);
            this.matrix = matrix;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (done) return;
            done = true;
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++)
                    canvas.setPixel(x, y, matrix[x][y] ? Color.BLACK : Color.WHITE);
        }
    }

    /** Crea el mapa QR y lo pone en el slot configurado. Devuelve true si pudo. */
    public static boolean giveQrMap(AuthStaff plugin, Player player, String uri) {
        try {
            boolean[][] matrix = qrMatrix(uri);
            MapView view = Bukkit.createMap(player.getWorld());
            view.setScale(MapView.Scale.CLOSEST);
            view.setUnlimitedTracking(false);
            for (MapRenderer r : view.getRenderers().toArray(new MapRenderer[0]))
                view.removeRenderer(r);
            view.addRenderer(new QrRenderer(matrix));
            // Render anticipado para que el mapa no salga en blanco
            ItemStack map = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) map.getItemMeta();
            meta.setMapView(view);
            meta.setDisplayName("§6§lCódigo QR 2FA");
            meta.setLore(java.util.List.of("§7Escanea con Google/Microsoft Authenticator", "§7Luego usa §e/auth menu §7o escribe el código"));
            map.setItemMeta(meta);
            int slot = plugin.getConfig().getInt("qr-slot", 4);
            if (slot < 0 || slot > 35) slot = 4;
            player.getInventory().setItem(slot, map);
            player.sendMap(view);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo generar el QR para " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
