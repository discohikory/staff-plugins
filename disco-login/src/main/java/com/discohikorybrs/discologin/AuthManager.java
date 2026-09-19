package com.discohikorybrs.discologin;

import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * SQLite + SHA-256 con sal + verificación premium Mojang.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class AuthManager {

    public static class Account {
        String hash, salt, ip;
        long lastLogin;
        boolean premium;
    }

    private final DiscoLogin plugin;
    private Connection db;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthManager(DiscoLogin plugin) {
        this.plugin = plugin;
    }

    public void open() throws Exception {
        File f = new File(plugin.getDataFolder(), "users.db");
        plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "name TEXT PRIMARY KEY, hash TEXT, salt TEXT, ip TEXT, "
                    + "last_login INTEGER DEFAULT 0, premium INTEGER DEFAULT 0)");
        }
    }

    public void close() {
        try {
            if (db != null) db.close();
        } catch (Exception ignored) {}
    }

    public synchronized Account get(String name) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT hash,salt,ip,last_login,premium FROM users WHERE name=?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Account a = new Account();
                a.hash = rs.getString(1);
                a.salt = rs.getString(2);
                a.ip = rs.getString(3);
                a.lastLogin = rs.getLong(4);
                a.premium = rs.getInt(5) == 1;
                return a;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void save(String name, String hash, String salt, String ip, boolean premium) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO users(name,hash,salt,ip,last_login,premium) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, ip);
            ps.setLong(5, System.currentTimeMillis());
            ps.setInt(6, premium ? 1 : 0);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo guardar " + name);
        }
    }

    public synchronized void touch(String name, String ip) {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE users SET last_login=?, ip=? WHERE name=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip);
            ps.setString(3, name.toLowerCase());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public synchronized void delete(String name) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM users WHERE name=?")) {
            ps.setString(1, name.toLowerCase());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public synchronized int countByIp(String ip) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE ip=?")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 99;
        }
    }

    public static String salt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static String hash(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((salt + ":" + password).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** ¿Existe como premium en Mojang? (Bedrock nunca). */
    public static boolean isPremium(String name) {
        if (name.startsWith(".")) return false;
        try {
            java.net.URL url = new java.net.URL(
                    "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
                    + java.net.URLEncoder.encode(name, "UTF-8"));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
