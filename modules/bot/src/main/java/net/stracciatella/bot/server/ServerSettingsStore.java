package net.stracciatella.bot.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

/**
 * {@code stracciatella/servers.json} — one {@link ServerSettings} per server the
 * player has configured the bot on, keyed by where the client is connected.
 *
 * <p>A map in one file rather than a file per server: the whole point is to see
 * at a glance which servers the bot is allowed to build on, and a directory of
 * near-identical files hides that. The key is the server address as the client
 * knows it, or {@code singleplayer/<world name>} for a local world — those are
 * the two identities the client actually has, and {@link #keyFor} is the only
 * place that decides, so a key that turns out to be wrong is wrong in one spot.
 *
 * <p>An unconfigured server is <em>not</em> an error and does not create a file
 * entry: {@link #current()} answers with a fresh tame {@link ServerSettings}
 * that nothing has been saved for. Writing a default entry on every join would
 * fill the file with servers the player only looked at once, and the defaults
 * are the same either way.
 */
public class ServerSettingsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STORE_FILE = "stracciatella/servers.json";
    private static final Type STORE_TYPE =
            new TypeToken<LinkedHashMap<String, ServerSettings>>() { }.getType();

    /** Key used when the client is not connected to anything at all. */
    private static final String UNKNOWN_KEY = "unknown";

    private static final Map<String, ServerSettings> byServer = new LinkedHashMap<>();

    /**
     * Identity of the server the client is currently on. Public because the
     * commands report it — "which server do my settings apply to" is the first
     * question a player has when a storage list looks empty.
     */
    public static String keyFor(Minecraft client) {
        var server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return server.ip;
        }
        var local = client.getSingleplayerServer();
        if (local != null) {
            return "singleplayer/" + local.getWorldData().getLevelName();
        }
        return UNKNOWN_KEY;
    }

    /** The dimension id of the level the player is in, e.g. {@code minecraft:overworld}. */
    public static String dimensionOf(Minecraft client) {
        return client.level != null ? client.level.dimension().identifier().toString() : "";
    }

    /**
     * Settings for the current server, creating a tame default set if this
     * server has none. The returned object is the stored one — edit it and call
     * {@link #save()}.
     */
    public static ServerSettings current() {
        return forKey(keyFor(Minecraft.getInstance()));
    }

    public static ServerSettings forKey(String key) {
        return byServer.computeIfAbsent(key, k -> new ServerSettings());
    }

    /** Every configured server, for the listing commands. */
    public static Map<String, ServerSettings> all() {
        return byServer;
    }

    public static void load() {
        byServer.clear();
        Path path = Path.of(STORE_FILE);
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            Map<String, ServerSettings> loaded = GSON.fromJson(reader, STORE_TYPE);
            if (loaded != null) {
                // Drop null values rather than trust the file: a hand-edited
                // entry with no body would otherwise hand a null ServerSettings
                // to a restock that is already walking.
                loaded.forEach((key, settings) -> {
                    if (settings != null) {
                        byServer.put(key, sanitize(settings));
                    }
                });
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken file must not stop the module from loading. The player
            // gets the tame defaults, which is the safe direction to fail in.
        }
    }

    /**
     * Fill in fields a hand-edited or older file left out. Every one of them
     * defaults to the conservative value, so a missing key can never be the
     * reason the bot places blocks or types a command.
     */
    private static ServerSettings sanitize(ServerSettings settings) {
        if (settings.exitStrategy == null) {
            settings.exitStrategy = ServerSettings.ExitStrategy.STAIRCASE;
        }
        if (settings.exitCommands == null) {
            settings.exitCommands = new java.util.ArrayList<>();
        }
        if (settings.storages == null) {
            settings.storages = new java.util.ArrayList<>();
        } else {
            settings.storages.removeIf(site -> site == null || site.dimension() == null);
        }
        return settings;
    }

    public static void save() {
        Path path = Path.of(STORE_FILE);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException ignored) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            GSON.toJson(byServer, STORE_TYPE, writer);
        } catch (IOException ignored) {
        }
    }
}
