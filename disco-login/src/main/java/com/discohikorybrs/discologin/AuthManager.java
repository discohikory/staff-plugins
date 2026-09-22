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
        String hash, salt, ip, platform;
        long lastLogin;
        boolean premium;
    }

    private final DiscoLogin plugin;
    private Connection db;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthManager(DiscoLogin plugin) {
        this.plugin = plugin;
    }

    private boolean mysql() {
        try {
            return plugin.getConfig().getBoolean("mysql.enabled", false);
        } catch (Exception e) {
            return false;
        }
    }

    private Connection mysqlConn() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = "jdbc:mysql://" + plugin.getConfig().getString("mysql.host")
                    + ":" + plugin.getConfig().getInt("mysql.port", 3306)
                    + "/" + plugin.getConfig().getString("mysql.database")
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            java.sql.Connection c = java.sql.DriverManager.getConnection(url,
                    plugin.getConfig().getString("mysql.user"),
                    plugin.getConfig().getString("mysql.password"));
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS disco_users ("
                        + "name VARCHAR(32) PRIMARY KEY, hash TEXT, salt TEXT, ip TEXT, "
                        + "last_login BIGINT DEFAULT 0, premium INTEGER DEFAULT 0, "
                        + "platform TEXT DEFAULT 'java')");
            }
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    public void open() throws Exception {
        File f = new File(plugin.getDataFolder(), "users.db");
        plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "name TEXT PRIMARY KEY, hash TEXT, salt TEXT, ip TEXT, "
                    + "last_login INTEGER DEFAULT 0, premium INTEGER DEFAULT 0, "
                    + "platform TEXT DEFAULT 'java')");
            try {
                st.executeUpdate("ALTER TABLE users ADD COLUMN platform TEXT DEFAULT 'java'");
            } catch (Exception ignored) {}
        }
    }

    public void close() {
        try {
            if (db != null) db.close();
        } catch (Exception ignored) {}
    }

    public synchronized Account get(String name) {
        if (mysql()) return mysqlGet(name);
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT hash,salt,ip,last_login,premium,platform FROM users WHERE name=?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Account a = new Account();
                a.hash = rs.getString(1);
                a.salt = rs.getString(2);
                a.ip = rs.getString(3);
                a.lastLogin = rs.getLong(4);
                a.premium = rs.getInt(5) == 1;
                try {
                    a.platform = rs.getString(6);
                } catch (Exception e) {
                    a.platform = "java";
                }
                if (a.platform == null) a.platform = "java";
                return a;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized Account mysqlGet(String name) {
        java.sql.Connection c = mysqlConn();
        if (c == null) return null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT hash,salt,ip,last_login,premium,platform FROM disco_users WHERE name=?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Account a = new Account();
                a.hash = rs.getString(1);
                a.salt = rs.getString(2);
                a.ip = rs.getString(3);
                a.lastLogin = rs.getLong(4);
                a.premium = rs.getInt(5) == 1;
                try {
                    a.platform = rs.getString(6);
                } catch (Exception e) {
                    a.platform = "java";
                }
                if (a.platform == null) a.platform = "java";
                return a;
            }
        } catch (Exception e) {
            return null;
        } finally {
            try {
                c.close();
            } catch (Exception ignored) {}
        }
    }

    public synchronized void save(String name, String hash, String salt, String ip, boolean premium) {
        save(name, hash, salt, ip, premium, "java");
    }

    public synchronized void save(String name, String hash, String salt, String ip,
                                  boolean premium, String platform) {
        if (mysql()) {
            mysqlSave(name, hash, salt, ip, premium, platform);
            return;
        }
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO users(name,hash,salt,ip,last_login,premium,platform) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, ip);
            ps.setLong(5, System.currentTimeMillis());
            ps.setInt(6, premium ? 1 : 0);
            ps.setString(7, platform == null ? "java" : platform);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo guardar " + name);
        }
    }

    private synchronized void mysqlSave(String name, String hash, String salt, String ip,
                                        boolean premium, String platform) {
        java.sql.Connection c = mysqlConn();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO disco_users(name,hash,salt,ip,last_login,premium,platform) VALUES(?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE hash=VALUES(hash),salt=VALUES(salt),ip=VALUES(ip),"
                + "last_login=VALUES(last_login),premium=VALUES(premium),platform=VALUES(platform)")) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, ip);
            ps.setLong(5, System.currentTimeMillis());
            ps.setInt(6, premium ? 1 : 0);
            ps.setString(7, platform == null ? "java" : platform);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo guardar " + name);
        } finally {
            try {
                c.close();
            } catch (Exception ignored) {}
        }
    }

    public synchronized void touch(String name, String ip) {
        if (mysql()) {
            Account a = mysqlGet(name);
            mysqlSave(name, a == null ? "" : a.hash, a == null ? "" : a.salt,
                    ip, a != null && a.premium, a == null ? "java" : a.platform);
            return;
        }
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE users SET last_login=?, ip=? WHERE name=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip);
            ps.setString(3, name.toLowerCase());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public synchronized void delete(String name) {
        if (mysql()) {
            java.sql.Connection c = mysqlConn();
            if (c == null) return;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM disco_users WHERE name=?")) {
                ps.setString(1, name.toLowerCase());
                ps.executeUpdate();
            } catch (Exception ignored) {
            } finally {
                try {
                    c.close();
                } catch (Exception ignored) {}
            }
            return;
        }
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM users WHERE name=?")) {
            ps.setString(1, name.toLowerCase());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public synchronized int countByIp(String ip) {
        if (mysql()) {
            java.sql.Connection c = mysqlConn();
            if (c == null) return 99;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM disco_users WHERE ip=?")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (Exception e) {
                return 99;
            } finally {
                try {
                    c.close();
                } catch (Exception ignored) {}
            }
        }
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
