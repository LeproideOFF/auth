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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class LoginServer {

    private static final Map<UUID, String> registeredUsers = new HashMap<>(); // En prod, utilisez une DB
    private static final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private static final String VELOCITY_SECRET = System.getProperty("velocity.secret", "votre_secret_ici");
    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        MinecraftServer minecraftServer;
        if (!VELOCITY_SECRET.equals("votre_secret_ici")) {
            minecraftServer = MinecraftServer.init(new Auth.Velocity(VELOCITY_SECRET));
        } else {
            minecraftServer = MinecraftServer.init();
        }

        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        // Instance vide (sans générateur pour consommer le moins possible)
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();

        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instanceContainer);
        });

        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            player.sendMessage(Component.text("-----------------------------------", NamedTextColor.GRAY));
            player.sendMessage(Component.text("Connecté au serveur de login sécurisé", NamedTextColor.YELLOW));
            if (registeredUsers.containsKey(player.getUuid())) {
                player.sendMessage(Component.text("Veuillez utiliser /login <motdepasse>", NamedTextColor.WHITE));
            } else {
                player.sendMessage(Component.text("Veuillez utiliser /register <motdepasse>", NamedTextColor.GOLD));
            }
            player.sendMessage(Component.text("-----------------------------------", NamedTextColor.GRAY));
        });

        // Commande /register
        Command registerCommand = new Command("register");
        var regPasswordArg = ArgumentType.String("password");
        registerCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            if (registeredUsers.containsKey(player.getUuid())) {
                player.sendMessage(Component.text("Vous êtes déjà enregistré. Utilisez /login.", NamedTextColor.RED));
                return;
            }
            String password = context.get(regPasswordArg);
            registeredUsers.put(player.getUuid(), password);
            player.sendMessage(Component.text("Enregistrement réussi ! Connectez-vous maintenant.", NamedTextColor.GREEN));
        }, regPasswordArg);

        // Commande /login
        Command loginCommand = new Command("login");
        var loginPasswordArg = ArgumentType.String("password");
        loginCommand.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            UUID uuid = player.getUuid();
            
            if (!registeredUsers.containsKey(uuid)) {
                player.sendMessage(Component.text("Vous devez d'abord vous enregistrer avec /register.", NamedTextColor.RED));
                return;
            }

            String password = context.get(loginPasswordArg);
            if (registeredUsers.get(uuid).equals(password)) {
                player.sendMessage(Component.text("Authentification réussie ! Redirection...", NamedTextColor.GREEN));
                loginAttempts.remove(uuid);
                redirect(player);
            } else {
                int attempts = loginAttempts.getOrDefault(uuid, 0) + 1;
                loginAttempts.put(uuid, attempts);
                if (attempts >= 3) {
                    player.kick(Component.text("Trop de tentatives échouées (3/3).", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("Mot de passe incorrect (" + attempts + "/3).", NamedTextColor.RED));
                }
            }
        }, loginPasswordArg);

        MinecraftServer.getCommandManager().register(registerCommand);
        MinecraftServer.getCommandManager().register(loginCommand);

        int port = Integer.getInteger("server.port", 25500);
        System.out.println("Micro-serveur de login (Mapless) démarré sur le port " + port);
        minecraftServer.start("0.0.0.0", port);
    }

    private static void redirect(Player player) {
        String[] lobbies = {"lobby1", "lobby2", "lobby3", "lobby4", "lobby5"};
        String targetLobby = lobbies[RANDOM.nextInt(lobbies.length)];
        
        // Utilisation du plugin message "BungeeCord" (supporté par Velocity) pour connecter le joueur
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(targetLobby);
            
            player.sendPluginMessage("bungeecord:main", b.toByteArray());
            System.out.println("Redirection de " + player.getUsername() + " vers " + targetLobby);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
