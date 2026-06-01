package com.auth;

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
    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        initDatabase();
        
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
            event.setSpawningInstance(instanceContainer);
        });

        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            String ip = getCleanIp(player);
            UUID uuid = player.getUuid();
            
            // Anti-Spam IP
            long now = System.currentTimeMillis();
            if (ipRateLimit.containsKey(ip) && (now - ipRateLimit.get(ip) < 2000)) {
                player.kick(Component.text("Connexions trop rapides.", NamedTextColor.RED));
                return;
            }
            ipRateLimit.put(ip, now);

            // Vérification auto-login (même IP)
            if (isRegistered(uuid) && ip.equals(getLastIp(uuid))) {
                player.sendMessage(Component.text("Bon retour ! IP identique, entrez le captcha.", NamedTextColor.GREEN));
                generateAndSendCaptcha(player);
                return;
            }

            // Message de bienvenue
            player.sendMessage(Component.text("-----------------------------------", NamedTextColor.GRAY));
            if (isRegistered(uuid)) {
                player.sendMessage(Component.text("Veuillez utiliser /login <password>", NamedTextColor.YELLOW));
            } else {
                player.sendMessage(Component.text("Bienvenue ! Veuillez faire /register <password>", NamedTextColor.GOLD));
            }
            player.sendMessage(Component.text("-----------------------------------", NamedTextColor.GRAY));

            // Détection Bedrock (Geyser utilise souvent des pseudos commençant par *)
            if (player.getUsername().startsWith("*")) {
                player.sendMessage(Component.text("Joueur Bedrock détecté.", NamedTextColor.AQUA));
            }
        });

        // Commande /register
        Command registerCommand = new Command("register");
        var regPass = ArgumentType.String("password");
        registerCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            String ip = getCleanIp(player);
            if (isRegistered(player.getUuid())) {
                player.sendMessage(Component.text("Déjà enregistré.", NamedTextColor.RED));
                return;
            }
            if (getIpAccountCount(ip) >= 2) {
                player.kick(Component.text("Max 2 comptes par IP.", NamedTextColor.RED));
                return;
            }
            String password = context.get(regPass);
            if (password.length() < 6) {
                player.sendMessage(Component.text("Mot de passe trop court.", NamedTextColor.RED));
                return;
            }
            saveUser(player.getUuid(), BCrypt.hashpw(password, BCrypt.gensalt()), ip);
            player.sendMessage(Component.text("Enregistré !", NamedTextColor.GREEN));
            generateAndSendCaptcha(player);
        }, regPass);

        // Commande /login
        Command loginCommand = new Command("login");
        var logPass = ArgumentType.String("password");
        loginCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            UUID uuid = player.getUuid();
            if (!isRegistered(uuid)) {
                player.sendMessage(Component.text("Utilisez /register.", NamedTextColor.RED));
                return;
            }
            if (BCrypt.checkpw(context.get(logPass), getHashedPassword(uuid))) {
                updateIp(uuid, getCleanIp(player));
                loginAttempts.remove(uuid);
                generateAndSendCaptcha(player);
            } else {
                int att = loginAttempts.getOrDefault(uuid, 0) + 1;
                loginAttempts.put(uuid, att);
                logSecurity("Échec login: " + player.getUsername() + " [" + getCleanIp(player) + "] (" + att + "/3)");
                if (att >= 3) player.kick(Component.text("3 échecs.", NamedTextColor.RED));
                else player.sendMessage(Component.text("Mauvais mot de passe.", NamedTextColor.RED));
            }
        }, logPass);

        // Commande /confirm
        Command confirmCommand = new Command("confirm");
        var codeArg = ArgumentType.String("code");
        confirmCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            if (pendingCaptcha.getOrDefault(player.getUuid(), "").equals(context.get(codeArg))) {
                pendingCaptcha.remove(player.getUuid());
                redirect(player);
            } else {
                logSecurity("Échec captcha: " + player.getUsername() + " [" + getCleanIp(player) + "]");
                player.kick(Component.text("Captcha invalide.", NamedTextColor.RED));
            }
        }, codeArg);

        MinecraftServer.getCommandManager().register(registerCommand);
        MinecraftServer.getCommandManager().register(loginCommand);
        MinecraftServer.getCommandManager().register(confirmCommand);

        // Monitoring
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
            System.out.println("[MONITOR] RAM: " + used + " MB | SQL: Active");
        }).repeat(Duration.ofMinutes(1)).schedule();

        int port = Integer.getInteger("server.port", 25500);
        System.gc();
        minecraftServer.start("0.0.0.0", port);
    }

    private static void initDatabase() {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:auth.db");
            Statement s = db.createStatement();
            s.execute("CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY, password TEXT, last_ip TEXT)");
            System.out.println("SQLite initialisé avec succès.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static boolean isRegistered(UUID uuid) {
        try (PreparedStatement ps = db.prepareStatement("SELECT uuid FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    private static void saveUser(UUID uuid, String hash, String ip) {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO users VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, hash);
            ps.setString(3, ip);
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
            ps.setString(1, ip);
            ps.setString(2, uuid.toString());
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
        player.sendMessage(Component.text("\n[ANTIBOT] Code: ", NamedTextColor.RED)
                .append(Component.text(code, NamedTextColor.YELLOW, net.kyori.adventure.text.format.TextDecoration.BOLD)));
        player.sendMessage(Component.text("Tapez /confirm " + code, NamedTextColor.GRAY));
    }

    private static void logSecurity(String msg) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true); PrintWriter pw = new PrintWriter(fw)) {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"));
            pw.println("[" + time + "] " + msg);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static String getCleanIp(Player player) {
        String addr = player.getPlayerConnection().getRemoteAddress().toString();
        if (addr.startsWith("/")) addr = addr.substring(1);
        return addr.split(":")[0];
    }

    private static void redirect(Player player) {
        String[] lobbies = {"lobby1", "lobby2", "lobby3", "lobby4", "lobby5"};
        String target = lobbies[RANDOM.nextInt(lobbies.length)];
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(target);
            player.sendPluginMessage("bungeecord:main", b.toByteArray());
            System.out.println("Redirection: " + player.getUsername() + " -> " + target);
        } catch (IOException e) { e.printStackTrace(); }
    }
}
