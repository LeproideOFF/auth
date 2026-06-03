package com.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LoginServer {

    private static Connection db;
    private static final String LOG_FILE = "security.log";
    private static final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private static final Map<String, Long> ipRateLimit = new ConcurrentHashMap<>();
    private static final Map<UUID, String> pendingCaptcha = new HashMap<>();
    private static final String VELOCITY_SECRET = System.getProperty("velocity.secret", "");
    private static final String ADMIN_TOKEN = System.getProperty("admin.token", "admin123");
    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        initDatabase();
        startWebServer();
        
        MinecraftServer minecraftServer;
        if (!VELOCITY_SECRET.isEmpty() && !VELOCITY_SECRET.equals("votre_secret_ici")) {
            minecraftServer = MinecraftServer.init(new Auth.Velocity(VELOCITY_SECRET));
        } else {
            minecraftServer = MinecraftServer.init();
        }

        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();

        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            Player player = event.getPlayer();
            String ip = getCleanIp(player);
            long now = System.currentTimeMillis();

            if (ipRateLimit.containsKey(ip) && (now - ipRateLimit.get(ip) < 1500)) {
                player.kick(Component.text("[FortiMC]Connexions trop rapides.", NamedTextColor.RED));
                return;
            }
            ipRateLimit.put(ip, now);
            event.setSpawningInstance(instanceContainer);
        });

        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            String ip = getCleanIp(player);
            UUID uuid = player.getUuid();

            if (isRegistered(uuid) && ip.equals(getLastIp(uuid))) {
                player.sendMessage(Component.text("IP reconnue, entrez le captcha.", NamedTextColor.GREEN));
                generateAndSendCaptcha(player);
                return;
            }

            player.sendMessage(Component.text("--- FortiMC ---", NamedTextColor.YELLOW));
            if (isRegistered(uuid)) player.sendMessage(Component.text("[FortiMC]Tapez /login <pass>", NamedTextColor.WHITE));
            else player.sendMessage(Component.text("[FortiMC]Tapez /register <pass>", NamedTextColor.GOLD));
        });

        // Commandes
        Command registerCommand = new Command("register");
        var regPass = ArgumentType.String("password");
        registerCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            if (isRegistered(player.getUuid())) return;
            if (getIpAccountCount(getCleanIp(player)) >= 2) {
                player.kick(Component.text("[FortiMC]Max 2 comptes par IP.", NamedTextColor.RED));
                return;
            }
            saveUser(player.getUuid(), BCrypt.hashpw(context.get(regPass), BCrypt.gensalt()), getCleanIp(player));
            player.sendMessage(Component.text("[FortiMC]Enregistré !", NamedTextColor.GREEN));
            generateAndSendCaptcha(player);
        }, regPass);

        Command loginCommand = new Command("login");
        var logPass = ArgumentType.String("password");
        loginCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            UUID uuid = player.getUuid();
            if (BCrypt.checkpw(context.get(logPass), getHashedPassword(uuid))) {
                updateIp(uuid, getCleanIp(player));
                loginAttempts.remove(uuid);
                generateAndSendCaptcha(player);
            } else {
                int att = loginAttempts.getOrDefault(uuid, 0) + 1;
                loginAttempts.put(uuid, att);
                logSecurity("Échec login: " + player.getUsername() + " (" + att + "/3)");
                if (att >= 3) player.kick(Component.text("3 échecs.", NamedTextColor.RED));
                else player.sendMessage(Component.text("[FortiMC]Mauvais mot de passe.", NamedTextColor.RED));
            }
        }, logPass);

        Command confirmCommand = new Command("confirm");
        var codeArg = ArgumentType.String("code");
        confirmCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            if (pendingCaptcha.getOrDefault(player.getUuid(), "").equals(context.get(codeArg))) {
                pendingCaptcha.remove(player.getUuid());
                redirect(player);
            } else player.kick(Component.text("[FortiMC]Captcha invalide.", NamedTextColor.RED));
        }, codeArg);

        MinecraftServer.getCommandManager().register(registerCommand);
        MinecraftServer.getCommandManager().register(loginCommand);
        MinecraftServer.getCommandManager().register(confirmCommand);

        int port = Integer.getInteger("server.port", 25500);
        System.gc();
        minecraftServer.start("0.0.0.0", port);
    }

    private static void startWebServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/", new AdminHandler());
            server.setExecutor(null);
            server.start();
            System.out.println("[CONSOLE]Panel Admin Web démarré sur http://localhost:8080 (Token: " + ADMIN_TOKEN + ")");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class AdminHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("token=" + ADMIN_TOKEN)) {
                String response = "Accès refusé. Token invalide.";
                exchange.sendResponseHeaders(403, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String formData = br.readLine();
                Map<String, String> params = parseFormData(formData);
                
                if (params.containsKey("delete")) {
                    deleteUser(params.get("delete"));
                } else if (params.containsKey("update_uuid")) {
                    updateUserPassword(params.get("update_uuid"), params.get("new_pass"));
                }
            }

            StringBuilder html = new StringBuilder("<html><head><meta charset='UTF-8'><style>body{font-family:sans-serif;background:#f0f0f0;padding:20px;} table{background:white;border-collapse:collapse;width:100%;} th,td{border:1px solid #ddd;padding:12px;text-align:left;} th{background:#333;color:white;}</style></head><body>");
            html.append("<h1>Panel Admin - Auth Server</h1>");
            html.append("<table><tr><th>UUID</th><th>Last IP</th><th>Actions</th></tr>");

            try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM users")) {
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    html.append("<tr><td>").append(uuid).append("</td><td>").append(rs.getString("last_ip")).append("</td>");
                    html.append("<td><form method='POST' style='display:inline'><input type='hidden' name='delete' value='").append(uuid).append("'><input type='submit' value='Supprimer' onclick='return confirm(\"Sûr ?\")'></form> ");
                    html.append("<form method='POST' style='display:inline'><input type='hidden' name='update_uuid' value='").append(uuid).append("'><input type='text' name='new_pass' placeholder='Nouveau MDP'><input type='submit' value='Modifier'></form></td></tr>");
                }
            } catch (SQLException e) { e.printStackTrace(); }

            html.append("</table></body></html>");
            byte[] response = html.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    private static Map<String, String> parseFormData(String formData) {
        Map<String, String> map = new HashMap<>();
        if (formData == null) return map;
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    private static void deleteUser(String uuid) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
            System.out.println("WebAdmin: Utilisateur supprimé -> " + uuid);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private static void updateUserPassword(String uuid, String newPass) {
        if (newPass == null || newPass.length() < 6) return;
        try (PreparedStatement ps = db.prepareStatement("UPDATE users SET password = ? WHERE uuid = ?")) {
            ps.setString(1, BCrypt.hashpw(newPass, BCrypt.gensalt()));
            ps.setString(2, uuid);
            ps.executeUpdate();
            System.out.println("WebAdmin: Mot de passe modifié -> " + uuid);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private static void initDatabase() {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:auth.db");
            Statement s = db.createStatement();
            s.execute("CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY, password TEXT, last_ip TEXT)");
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private static boolean isRegistered(UUID uuid) {
        try (PreparedStatement ps = db.prepareStatement("SELECT uuid FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    private static void saveUser(UUID uuid, String hash, String ip) {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO users VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString()); ps.setString(2, hash); ps.setString(3, ip);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private static String getHashedPassword(UUID uuid) {
        try (PreparedStatement ps = db.prepareStatement("SELECT password FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("password") : "";
        } catch (SQLException e) { return ""; }
    }

    private static String getLastIp(UUID uuid) {
        try (PreparedStatement ps = db.prepareStatement("SELECT last_ip FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("last_ip") : "";
        } catch (SQLException e) { return ""; }
    }

    private static void updateIp(UUID uuid, String ip) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE users SET last_ip = ? WHERE uuid = ?")) {
            ps.setString(1, ip); ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private static int getIpAccountCount(String ip) {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM users WHERE last_ip = ?")) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { return 0; }
    }

    private static void generateAndSendCaptcha(Player player) {
        String code = String.format("%04d", RANDOM.nextInt(10000));
        pendingCaptcha.put(player.getUuid(), code);
        player.sendMessage(Component.text("\n[FortiMC] Code: ", NamedTextColor.RED).append(Component.text(code, NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("[FortiMC]Tapez /confirm " + code, NamedTextColor.GRAY));
    }

    private static void logSecurity(String msg) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) + "] " + msg);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static String getCleanIp(Player player) {
        String addr = player.getPlayerConnection().getRemoteAddress().toString();
        if (addr.contains("/")) addr = addr.substring(addr.lastIndexOf("/") + 1);
        return addr.split(":")[0];
    }

    private static void redirect(Player player) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF("lobby" + (RANDOM.nextInt(5) + 1));
            player.sendPluginMessage("bungeecord:main", b.toByteArray());
        } catch (IOException e) { e.printStackTrace(); }
    }
}

