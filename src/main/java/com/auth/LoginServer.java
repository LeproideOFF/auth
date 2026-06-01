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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LoginServer {

    // Stockage (En prod: Utilisez une base de données !)
    private static final Map<UUID, String> userPasswords = new HashMap<>(); // UUID -> Hash BCrypt
    private static final Map<UUID, String> userLastIp = new HashMap<>();    // UUID -> Last IP
    private static final Map<String, Set<UUID>> ipAccounts = new HashMap<>(); // IP -> Set of UUIDs
    
    // États temporaires
    private static final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private static final Map<String, Long> ipRateLimit = new ConcurrentHashMap<>();
    private static final Map<UUID, String> pendingCaptcha = new HashMap<>(); // UUID -> 4-digit code
    
    private static final String VELOCITY_SECRET = System.getProperty("velocity.secret", "votre_secret_ici");
    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        MinecraftServer minecraftServer;
        if (VELOCITY_SECRET != null && !VELOCITY_SECRET.isEmpty() && !VELOCITY_SECRET.equals("votre_secret_ici")) {
            System.out.println("Support Velocity activé.");
            minecraftServer = MinecraftServer.init(new Auth.Velocity(VELOCITY_SECRET));
        } else {
            System.out.println("Support Velocity désactivé (Secret vide ou par défaut).");
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
            
            // Protection : Rate Limit par IP
            long now = System.currentTimeMillis();
            if (ipRateLimit.containsKey(ip) && (now - ipRateLimit.get(ip) < 2000)) {
                player.kick(Component.text("Trop de connexions. Attendez un peu.", NamedTextColor.RED));
                return;
            }
            ipRateLimit.put(ip, now);

            // Protection : Redemander le MDP seulement si l'IP a changé
            if (userPasswords.containsKey(uuid) && ip.equals(userLastIp.get(uuid))) {
                player.sendMessage(Component.text("IP reconnue. Connexion automatique...", NamedTextColor.GREEN));
                generateAndSendCaptcha(player);
                return;
            }

            player.sendMessage(Component.text("-----------------------------------", NamedTextColor.GRAY));
            player.sendMessage(Component.text("Serveur de Login Ultra-Sécurisé", NamedTextColor.YELLOW));
            if (userPasswords.containsKey(uuid)) {
                player.sendMessage(Component.text("Veuillez utiliser /login <motdepasse>", NamedTextColor.WHITE));
            } else {
                player.sendMessage(Component.text("Veuillez utiliser /register <motdepasse>", NamedTextColor.GOLD));
            }
            player.sendMessage(Component.text("-----------------------------------", NamedTextColor.GRAY));

            // Auto-kick si inactivé
            MinecraftServer.getSchedulerManager().buildTask(() -> {
                if (player.isOnline() && !isFullyAuthenticated(player)) {
                    player.kick(Component.text("Délai d'authentification dépassé.", NamedTextColor.RED));
                }
            }).delay(Duration.ofSeconds(60)).schedule();
        });

        // Commande /register
        Command registerCommand = new Command("register");
        var regPasswordArg = ArgumentType.String("password");
        registerCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            UUID uuid = player.getUuid();
            String ip = getCleanIp(player);

            if (userPasswords.containsKey(uuid)) {
                player.sendMessage(Component.text("Déjà enregistré.", NamedTextColor.RED));
                return;
            }

            // Protection E: Max 2 comptes par IP
            Set<UUID> accounts = ipAccounts.getOrDefault(ip, new HashSet<>());
            if (accounts.size() >= 2 && !accounts.contains(uuid)) {
                player.kick(Component.text("Maximum 2 comptes par IP autorisé.", NamedTextColor.RED));
                return;
            }

            String password = context.get(regPasswordArg);
            if (password.length() < 6) {
                player.sendMessage(Component.text("MDP trop court (min 6).", NamedTextColor.RED));
                return;
            }

            // Protection C: BCrypt Hash
            String hashed = BCrypt.hashpw(password, BCrypt.gensalt());
            userPasswords.put(uuid, hashed);
            userLastIp.put(uuid, ip);
            accounts.add(uuid);
            ipAccounts.put(ip, accounts);

            player.sendMessage(Component.text("Enregistré avec succès !", NamedTextColor.GREEN));
            generateAndSendCaptcha(player);
        }, regPasswordArg);

        // Commande /login
        Command loginCommand = new Command("login");
        var loginPasswordArg = ArgumentType.String("password");
        loginCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            UUID uuid = player.getUuid();
            
            if (!userPasswords.containsKey(uuid)) {
                player.sendMessage(Component.text("Utilisez /register d'abord.", NamedTextColor.RED));
                return;
            }

            String password = context.get(loginPasswordArg);
            if (BCrypt.checkpw(password, userPasswords.get(uuid))) {
                userLastIp.put(uuid, getCleanIp(player));
                loginAttempts.remove(uuid);
                generateAndSendCaptcha(player);
            } else {
                int attempts = loginAttempts.getOrDefault(uuid, 0) + 1;
                loginAttempts.put(uuid, attempts);
                if (attempts >= 3) {
                    player.kick(Component.text("3 échecs. Proxy kick.", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("Mauvais MDP (" + attempts + "/3).", NamedTextColor.RED));
                }
            }
        }, loginPasswordArg);

        // Commande /captcha
        Command captchaCommand = new Command("confirm");
        var codeArg = ArgumentType.String("code");
        captchaCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            UUID uuid = player.getUuid();
            String input = context.get(codeArg);

            if (pendingCaptcha.containsKey(uuid) && pendingCaptcha.get(uuid).equals(input)) {
                pendingCaptcha.remove(uuid);
                player.sendMessage(Component.text("Vérification réussie ! Redirection...", NamedTextColor.GREEN));
                redirect(player);
            } else {
                player.kick(Component.text("Code invalide. Bot détecté ?", NamedTextColor.RED));
            }
        }, codeArg);

        MinecraftServer.getCommandManager().register(registerCommand);
        MinecraftServer.getCommandManager().register(loginCommand);
        MinecraftServer.getCommandManager().register(captchaCommand);

        int port = Integer.getInteger("server.port", 25500);
        System.out.println("Micro-serveur de login (ULTRA-SECURE) démarré sur le port " + port);
        minecraftServer.start("0.0.0.0", port);
    }

    private static void generateAndSendCaptcha(Player player) {
        String code = String.format("%04d", RANDOM.nextInt(10000));
        pendingCaptcha.put(player.getUuid(), code);
        player.sendMessage(Component.text("\n[ANTI-BOT]", NamedTextColor.RED));
        player.sendMessage(Component.text("Veuillez entrer le code suivant : ", NamedTextColor.WHITE)
                .append(Component.text(code, NamedTextColor.YELLOW, net.kyori.adventure.text.format.TextDecoration.BOLD)));
        player.sendMessage(Component.text("Tapez : /confirm " + code, NamedTextColor.GRAY));
    }

    private static boolean isFullyAuthenticated(Player player) {
        return !pendingCaptcha.containsKey(player.getUuid()) && (userPasswords.containsKey(player.getUuid()));
    }

    private static String getCleanIp(Player player) {
        String addr = player.getPlayerConnection().getRemoteAddress().toString();
        if (addr.startsWith("/")) addr = addr.substring(1);
        return addr.split(":")[0];
    }

    private static void redirect(Player player) {
        String[] lobbies = {"lobby1", "lobby2", "lobby3", "lobby4", "lobby5"};
        String targetLobby = lobbies[RANDOM.nextInt(lobbies.length)];
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(targetLobby);
            player.sendPluginMessage("bungeecord:main", b.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
