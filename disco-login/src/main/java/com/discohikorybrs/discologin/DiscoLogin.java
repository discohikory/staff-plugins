package com.discohikorybrs.discologin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro, login, sesiones y premium. Congela hasta validar.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public class DiscoLogin extends JavaPlugin implements CommandExecutor {

    private AuthManager auth;
    private final Set<UUID> logged = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> barTasks = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.boss.BossBar> bars = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        auth = new AuthManager(this);
        try {
            auth.open();
        } catch (Exception e) {
            getLogger().severe("Sin base de datos. Desactivando.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new LoginListener(this), this);
        for (String c : new String[]{"register", "login", "logout", "changepassword", "unregister", "premium", "soy"})
            getCommand(c).setExecutor(this);
        // Canal entre servidores: login en uno = logeado en todos
        getServer().getMessenger().registerOutgoingPluginChannel(this, "discologin:main");
        getServer().getMessenger().registerIncomingPluginChannel(this, "discologin:main",
                (channel, player, bytes) -> {
                    try {
                        java.io.DataInputStream in = new java.io.DataInputStream(
                                new java.io.ByteArrayInputStream(bytes));
                        String tag = in.readUTF();
                        String name = in.readUTF();
                        if (!tag.equals("login")) return;
                        Player t = Bukkit.getPlayerExact(name);
                        if (t != null && t.isOnline() && !isLogged(t)) {
                            Bukkit.getScheduler().runTask(this, () -> {
                                if (t.isOnline() && !isLogged(t)) forceLogin(t, msg("logged"));
                            });
                        }
                    } catch (Exception ignored) {}
                });
        getLogger().info("DiscoLogin v1.0.0 por Discohikorybrs activado.");
    }

    @Override
    public void onDisable() {
        if (auth != null) auth.close();
    }

    public AuthManager auth() {
        return auth;
    }

    public boolean isLogged(Player p) {
        return logged.contains(p.getUniqueId());
    }

    private String msg(String path) {
        return getConfig().getString("messages.prefix", "")
                + getConfig().getString("messages." + path, "");
    }

    private String ipOf(Player p) {
        try {
            return p.getAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "?";
        }
    }

    /** Llamado al entrar: auto-detecta todo, sin preguntas. */
    void handleJoin(Player p) {
        logged.remove(p.getUniqueId());
        cancelTask(p);
        removeBar(p);
        // Pase entre servidores: ¿ya se logeó en otra modalidad hace poco?
        if (getConfig().getBoolean("mysql.enabled", false)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                if (hasRecentSession(p.getUniqueId())) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (p.isOnline() && !isLogged(p))
                            forceLogin(p, "§aSesión de la red válida. ¡Hola de nuevo!");
                    });
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> detectAndGo(p));
            });
            return;
        }
        detectAndGo(p);
    }

    /** Detección automática: bedrock, premium Mojang+UUID, o registro. */
    private void detectAndGo(Player p) {
        String clean = p.getName().replaceFirst("^" +
                java.util.regex.Pattern.quote(
                        getConfig().getString("bedrock-prefix", ".")), "");
        final boolean bedrock = !clean.equals(p.getName())
                || p.getName().startsWith(getConfig().getString("bedrock-prefix", "."));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // 1. Bedrock: entra directo, marcado bedrock
            if (bedrock) {
                auth.save(p.getName(), "", "", ipOf(p), false, "bedrock");
                pushMcStatus(p.getName(), p.getUniqueId().toString(), false, "bedrock");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (p.isOnline() && !isLogged(p)) forceLogin(p, "§a¡Bienvenido Bedrock! 🎮");
                });
                return;
            }
            // 2. Mojang API: ¿premium y coincide el UUID?
            MojangResult mj = mojangLookup(clean);
            if (mj != null && mj.uuid.equals(p.getUniqueId())) {
                final String real = mj.name;
                auth.save(real, "", "", ipOf(p), true, "java");
                pushMcStatus(real, p.getUniqueId().toString(), true, "java");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (p.isOnline() && !isLogged(p)) forceLogin(p, msg("premium-welcome"));
                });
                return;
            }
            // 3. No premium: registro + login con clave
            Bukkit.getScheduler().runTask(this, () -> prompt(p, auth.get(p.getName()), ipOf(p)));
        });
    }

    private static class MojangResult {
        String name;
        UUID uuid;
    }

    /** Consulta Mojang: devuelve UUID real si es premium, null si no. */
    private static MojangResult mojangLookup(String name) {
        try {
            java.net.URL url = new java.net.URL(
                    "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
                    + java.net.URLEncoder.encode(name, "UTF-8"));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");
            c.disconnect();
            String id = body.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f]{32})\".*", "$1");
            String nm = body.replaceAll(".*\"name\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            if (id.length() != 32) return null;
            MojangResult r = new MojangResult();
            r.name = nm;
            r.uuid = UUID.fromString(id.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** Registra premium/plataforma en Supabase para que el bot lo muestre. */
    private void pushMcStatus(String mc, String uuid, boolean premium, String platform) {
        if (!getConfig().getBoolean("supabase.enabled", false)) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String url = getConfig().getString("supabase.url") + "/rest/v1/mc_premium";
                String json = "{\"mc\":\"" + mc.replace("\"", "") + "\",\"uuid\":\"" + uuid
                        + "\",\"premium\":" + premium + ",\"platform\":\"" + platform
                        + "\",\"updated\":" + System.currentTimeMillis() + "}";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("apikey", getConfig().getString("supabase.key"));
                c.setRequestProperty("Authorization",
                        "Bearer " + getConfig().getString("supabase.key"));
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Prefer", "resolution=merge-duplicates");
                c.setDoOutput(true);
                c.getOutputStream().write(json.getBytes("UTF-8"));
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
        });
    }

    private void premiumOrPrompt(Player p) {
        String ip = ipOf(p);
        AuthManager.Account a = auth.get(p.getName());
        // Premium con auto-login: verifica Mojang en async
        if (getConfig().getBoolean("premium-auto-login", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                if (AuthManager.isPremium(p.getName())) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (!p.isOnline()) return;
                        AuthManager.Account acc = auth.get(p.getName());
                        auth.save(p.getName(), acc == null ? "" : acc.hash,
                                acc == null ? "" : acc.salt, ipOf(p), true);
                        forceLogin(p, msg("premium-welcome"));
                    });
                } else {
                    Bukkit.getScheduler().runTask(this, () -> prompt(p, a, ip));
                }
            });
            return;
        }
        prompt(p, a, ip);
    }

    /** ¿Hay sesión fresca de OTRO servidor? */
    private boolean hasRecentSession(UUID uuid) {
        java.sql.Connection c = mysqlConn();
        if (c == null) return false;
        try {
            String mine = getConfig().getString("server-name", "lobby");
            long since = System.currentTimeMillis()
                    - getConfig().getInt("cross-server-minutes", 10) * 60000L;
            java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT server FROM disco_sessions WHERE uuid=? AND time>?");
            ps.setString(1, uuid.toString());
            ps.setLong(2, since);
            java.sql.ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (!mine.equalsIgnoreCase(rs.getString(1))) {
                    rs.close();
                    ps.close();
                    c.close();
                    return true;
                }
            }
            rs.close();
            ps.close();
            c.close();
        } catch (Exception ignored) {}
        return false;
    }

    private void writeSession(UUID uuid) {
        java.sql.Connection c = mysqlConn();
        if (c == null) return;
        try {
            java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO disco_sessions(uuid,server,time) VALUES(?,?,?) "
                    + "ON DUPLICATE KEY UPDATE server=VALUES(server), time=VALUES(time)");
            ps.setString(1, uuid.toString());
            ps.setString(2, getConfig().getString("server-name", "lobby"));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
            c.close();
        } catch (Exception ignored) {}
    }

    private java.sql.Connection mysqlConn() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = "jdbc:mysql://" + getConfig().getString("mysql.host")
                    + ":" + getConfig().getInt("mysql.port", 3306)
                    + "/" + getConfig().getString("mysql.database")
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            java.sql.Connection c = java.sql.DriverManager.getConnection(url,
                    getConfig().getString("mysql.user"),
                    getConfig().getString("mysql.password"));
            try (java.sql.Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS disco_sessions ("
                        + "uuid VARCHAR(36) PRIMARY KEY, server VARCHAR(32), time BIGINT)");
            }
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private void prompt(Player p, AuthManager.Account a, String ip) {
        if (!p.isOnline()) return;
        // Sin cuenta: directo a /register (sin preguntas, Mojang ya decidió)
        if (a == null) {
            p.sendMessage(msg("need-register"));
            return;
        } else {
            p.sendMessage(msg("need-login"));
        }
        int timeout = getConfig().getInt("login-timeout", 120);
        if (timeout > 0) {
            p.sendTitle("§e§lBienvenido de nuevo!",
                    a == null ? "§7Usa §6/register <clave> <clave>" : "§7Usa §6/login <clave>",
                    10, 60, 10);
            org.bukkit.boss.BossBar bar = Bukkit.createBossBar("§cPor favor autentícate",
                    org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);
            bar.addPlayer(p);
            bars.put(p.getUniqueId(), bar);
            final int total = timeout;
            final int[] left = {timeout};
            int barTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!p.isOnline() || isLogged(p)) {
                    removeBar(p);
                    return;
                }
                left[0]--;
                bar.setTitle("§cPor favor autentícate — §e" + left[0] + "s");
                bar.setProgress(Math.max(0, left[0] / (double) total));
            }, 20L, 20L).getTaskId();
            barTasks.put(p.getUniqueId(), barTask);
            int id = Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !isLogged(p)) p.kickPlayer(msg("timeout"));
            }, timeout * 20L).getTaskId();
            tasks.put(p.getUniqueId(), id);
        }
    }

    private void removeBar(Player p) {
        org.bukkit.boss.BossBar b = bars.remove(p.getUniqueId());
        if (b != null) b.removeAll();
        Integer t = barTasks.remove(p.getUniqueId());
        if (t != null) Bukkit.getScheduler().cancelTask(t);
    }

    void handleQuit(Player p) {
        logged.remove(p.getUniqueId());
        cancelTask(p);
        removeBar(p);
        String gate = getConfig().getString("gate-permission", "skylith.2fa.required");
        if (gate != null && !gate.isEmpty()) {
            dispatch("lp user " + p.getName() + " permission unset " + gate);
        }
    }

    private void dispatch(String command) {
        try {
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        } catch (Exception ignored) {}
    }

    private void cancelTask(Player p) {
        Integer id = tasks.remove(p.getUniqueId());
        if (id != null) Bukkit.getScheduler().cancelTask(id);
    }

    private void forceLogin(Player p, String hello) {
        logged.add(p.getUniqueId());
        getLogger().info("Login OK para " + p.getName());
        cancelTask(p);
        removeBar(p);
        auth.touch(p.getName(), ipOf(p));
        if (getConfig().getBoolean("mysql.enabled", false)) {
            final UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeSession(id));
        }
        // Puerta 2: concede el permiso del 2FA solo si NO es premium.
        // Sin permiso no hay menú; premium salta el SkylithAuth.
        AuthManager.Account acc = auth.get(p.getName());
        boolean premium = acc != null && acc.premium;
        String gate = getConfig().getString("gate-permission", "skylith.2fa.required");
        if (gate != null && !gate.isEmpty()) {
            if (!premium && p.hasPermission("authstaff.required")) {
                dispatch("lp user " + p.getName() + " permission set " + gate + " true");
            } else {
                dispatch("lp user " + p.getName() + " permission unset " + gate);
            }
        }
        new LoginListener(this).unfreeze(p);
        p.sendMessage(hello);
        // Avisar a AuthStaff (si está) para que inicie el 2FA tras el logeo
        Bukkit.getPluginManager().callEvent(new DiscoLoginSuccessEvent(p));
        try {
            org.bukkit.plugin.Plugin as = Bukkit.getPluginManager().getPlugin("AuthStaff");
            if (as != null) {
                java.lang.reflect.Method m = as.getClass().getMethod("startFlow", Player.class);
                m.invoke(as, p);
            }
        } catch (Exception ignored) {}
        // Avisar a los otros servidores (solo Velocity/Bungee reenvía esto)
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(b);
            out.writeUTF("login");
            out.writeUTF(p.getName());
            p.sendPluginMessage(this, "discologin:main", b.toByteArray());
        } catch (Exception ignored) {}
    }

    /** Primera vez: pregunta clicable premium o no premium. */
    private void askFirstJoin(Player p) {
        p.sendMessage(msg("first-join"));
        net.md_5.bungee.api.chat.TextComponent yes =
                new net.md_5.bungee.api.chat.TextComponent("§a§l[✔ SOY PREMIUM] ");
        yes.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/soy premium"));
        yes.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.TextComponent[]{
                        new net.md_5.bungee.api.chat.TextComponent("§7Tengo MC comprado")}));
        net.md_5.bungee.api.chat.TextComponent no =
                new net.md_5.bungee.api.chat.TextComponent("§c§l[✘ NO PREMIUM] ");
        no.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/soy nopremium"));
        no.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.TextComponent[]{
                        new net.md_5.bungee.api.chat.TextComponent("§7Juego sin MC comprado")}));
        net.md_5.bungee.api.chat.TextComponent bed =
                new net.md_5.bungee.api.chat.TextComponent("§b§l[🎮 BEDROCK]");
        bed.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/soy bedrock"));
        bed.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.TextComponent[]{
                        new net.md_5.bungee.api.chat.TextComponent("§7Consola o móvil (Xbox/Play/Móvil)")}));
        p.spigot().sendMessage(yes, no, bed);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        if (n.equals("logout")) {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            if (!isLogged(p)) {
                p.sendMessage(msg("need-login"));
                return true;
            }
            logged.remove(p.getUniqueId());
            handleQuit(p);
            p.sendMessage("§eSesión cerrada. Vuelve a logearte.");
            prompt(p, auth.get(p.getName()), ipOf(p));
            return true;
        }
        if (n.equals("soy")) {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            if (a.length < 1) {
                askFirstJoin(p);
                return true;
            }
            if (a[0].equalsIgnoreCase("premium")) {
                p.sendMessage("§eVerificando tu cuenta premium...");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    boolean ok = AuthManager.isPremium(p.getName());
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (!p.isOnline()) return;
                        if (ok) {
                            auth.save(p.getName(), "", "", ipOf(p), true, "java");
                            forceLogin(p, msg("premium-welcome"));
                        } else {
                            p.sendMessage("§cNo eres premium. Regístrate: §6/register <clave> <clave>");
                        }
                    });
                });
                return true;
            }
            if (a[0].equalsIgnoreCase("bedrock")) {
                // Bedrock (consola/móvil): sin clave, entra directo y queda marcado
                auth.save(p.getName(), "", "", ipOf(p), false, "bedrock");
                forceLogin(p, "§a¡Bienvenido Bedrock! 🎮");
                return true;
            }
            p.sendMessage(msg("need-register"));
            return true;
        }
        if (n.equals("unregister")) {
            if (a.length < 1) {
                s.sendMessage("§eUso: /unregister <jugador>");
                return true;
            }
            auth.delete(a[0]);
            s.sendMessage(msg("unregistered").replace("{jugador}", a[0]));
            return true;
        }
        if (n.equals("premium")) {
            if (a.length < 1) {
                s.sendMessage("§eUso: /premium <jugador>");
                return true;
            }
            // Solo staff con MC comprado (premium real verificado)
            s.sendMessage("§eVerificando premium de " + a[0] + "...");
            final String target = a[0];
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                boolean ok = AuthManager.isPremium(target);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!ok) {
                        s.sendMessage("§c" + target + " no es premium (sin MC comprado).");
                        return;
                    }
                    AuthManager.Account acc = auth.get(target);
                    auth.save(target, acc == null ? "" : acc.hash, acc == null ? "" : acc.salt,
                            acc == null ? "" : acc.ip, true);
                    s.sendMessage(msg("premium-set").replace("{jugador}", target));
                });
            });
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Solo jugadores.");
            return true;
        }
        Player p = (Player) s;
        String ip = ipOf(p);
        if (n.equals("register")) {
            if (auth.get(p.getName()) != null) {
                p.sendMessage(msg("already"));
                return true;
            }
            if (a.length < 2) {
                p.sendMessage(msg("need-register"));
                return true;
            }
            if (!a[0].equals(a[1])) {
                p.sendMessage(msg("mismatch"));
                return true;
            }
            int min = getConfig().getInt("min-password-length", 5);
            if (a[0].length() < min) {
                p.sendMessage(msg("short").replace("{n}", String.valueOf(min)));
                return true;
            }
            if (auth.countByIp(ip) >= getConfig().getInt("max-accounts-per-ip", 3)) {
                p.sendMessage(msg("max-ip"));
                return true;
            }
            String salt = AuthManager.salt();
            auth.save(p.getName(), AuthManager.hash(salt, a[0]), salt, ip, false);
            forceLogin(p, msg("registered"));
            return true;
        }
        if (n.equals("login")) {
            if (isLogged(p)) return true;
            AuthManager.Account acc = auth.get(p.getName());
            if (acc == null || acc.hash == null || acc.hash.isEmpty()) {
                p.sendMessage(msg("no-account"));
                return true;
            }
            if (a.length < 1 || !AuthManager.hash(acc.salt, a[0]).equals(acc.hash)) {
                p.sendMessage(msg("wrong"));
                return true;
            }
            forceLogin(p, msg("logged"));
            return true;
        }
        if (n.equals("changepassword")) {
            AuthManager.Account acc = auth.get(p.getName());
            if (acc == null) {
                p.sendMessage(msg("no-account"));
                return true;
            }
            if (a.length < 2 || !AuthManager.hash(acc.salt, a[0]).equals(acc.hash)) {
                p.sendMessage(msg("wrong"));
                return true;
            }
            int min = getConfig().getInt("min-password-length", 5);
            if (a[1].length() < min) {
                p.sendMessage(msg("short").replace("{n}", String.valueOf(min)));
                return true;
            }
            String salt = AuthManager.salt();
            auth.save(p.getName(), AuthManager.hash(salt, a[1]), salt, ip, acc.premium);
            p.sendMessage(msg("changed"));
            return true;
        }
        return false;
    }
}
