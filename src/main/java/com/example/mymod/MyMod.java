package com.example.mymod;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.mymod.license.LicenseGuard;
import com.example.mymod.license.LicenseStatus;
import com.example.mymod.license.Messages;

/**
 * A Fabric server mod protected by SunLicense. The license is checked off the server thread once the
 * server has started and every {@code recheck-hours}; your features run only while it is valid
 * ({@link #active()}, {@link #startFeatures(MinecraftServer)}).
 */
public final class MyMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "mymod";
    static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /**
     * BuiltByBit and Voxel Shop replace this string with the buyer's license key when they download the
     * jar (create a "%%__SUNLICENSE__%%" placeholder in the store, see docs.sunlicense.com/integrations).
     * Builds from anywhere else keep it, and the key comes from config/mymod.properties instead.
     */
    private static final String BUILT_IN_KEY = "%%__SUNLICENSE__%%";

    /**
     * When SunLicense can't be reached and there is no valid offline license file: keep running (true,
     * the default: an outage on our side shouldn't take your customers' servers down) or stop (false).
     */
    static final boolean ALLOW_WHEN_UNREACHABLE = true;

    /** How soon to try again after no answer, in minutes. */
    private static final long RETRY_MINUTES = 10;

    private static volatile boolean active;

    private final ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mymod-license");
        t.setDaemon(true);
        return t;
    });
    private final Path dir = FabricLoader.getInstance().getConfigDir();
    private LicenseGuard guard;
    private volatile String licenseKey;
    private String serverId;
    private volatile LicenseStatus status;
    private ScheduledFuture<?> retry;
    private MinecraftServer server;

    /** True while the license allows your features to run. Check it in every feature. */
    public static boolean active() {
        return active;
    }

    @Override
    public void onInitializeServer() {
        try {
            guard = new LicenseGuard(LicenseGuard.Settings.load(MyMod.class.getResourceAsStream("/sunlicense.properties")),
                    dir.resolve("mymod-license.lic"));
            serverId = serverId();
            licenseKey = LicenseGuard.pickKey(BUILT_IN_KEY, loadConfig().getProperty("license-key", ""));
        } catch (IOException e) {
            LOG.error("Can't start the license check: {}", e.getMessage());
            return;
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("mymod")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> {
                            LicenseStatus s = status;
                            ctx.getSource().sendSystemMessage(Component.literal(s == null ? "The license hasn't been checked yet."
                                    : "License " + Messages.mask(licenseKey) + " (" + s.state() + "): " + s.message()));
                            return 1;
                        })
                        .then(Commands.literal("recheck").executes(ctx -> {
                            try {   // picks up a license key added to the config since start-up
                                licenseKey = LicenseGuard.pickKey(BUILT_IN_KEY, loadConfig().getProperty("license-key", ""));
                            } catch (IOException e) {
                                LOG.warn("Can't read config/mymod.properties: {}", e.getMessage());
                            }
                            ctx.getSource().sendSystemMessage(Component.literal("Checking the license…"));
                            checks.execute(this::check);
                            return 1;
                        }))));
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            checks.execute(this::check);
            long hours = Long.parseLong(config("recheck-hours", "6"));
            if (hours > 0) {
                checks.scheduleAtFixedRate(this::check, hours, hours, TimeUnit.HOURS);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> checks.shutdownNow());
    }

    /** Runs on the mod's own thread; anything touching the game goes to the server thread. */
    private void check() {
        LicenseStatus result = guard.check(licenseKey, serverId);
        status = result;
        server.execute(() -> apply(result));
    }

    private void apply(LicenseStatus result) {
        switch (result.state()) {
            case VALID, VALID_OFFLINE -> {
                if (!active) {
                    active = true;
                    LOG.info("License {}: {}", Messages.mask(licenseKey), result.message());
                    startFeatures(server);
                }
            }
            case REJECTED, NOT_CONFIGURED -> {
                LOG.error("License {}: {}", Messages.mask(licenseKey), result.message());
                LOG.error("MyMod is off until this is fixed (then run /mymod recheck).");
                active = false;
            }
            case UNREACHABLE -> {
                LOG.warn("{} Trying again in {} minutes.", result.message(), RETRY_MINUTES);
                if (ALLOW_WHEN_UNREACHABLE && !active) {
                    active = true;
                    startFeatures(server);
                } else if (!active) {
                    LOG.error("MyMod needs a license check to start; it will start once SunLicense answers.");
                }
                if (retry == null || retry.isDone()) {   // one retry at a time
                    retry = checks.schedule(this::check, RETRY_MINUTES, TimeUnit.MINUTES);
                }
            }
        }
    }

    /**
     * Your mod's features. Fabric can't unload a mod, so register events and commands in
     * onInitializeServer as usual and make each one return early unless {@link #active()}.
     */
    private void startFeatures(MinecraftServer server) {
        LOG.info("MyMod is running.");
    }

    private String config(String key, String fallback) {
        try {
            return loadConfig().getProperty(key, fallback).trim();
        } catch (IOException e) {
            return fallback;
        }
    }

    /** config/mymod.properties, written from the default on first start. */
    private Properties loadConfig() throws IOException {
        Path file = dir.resolve("mymod.properties");
        if (!Files.exists(file)) {
            Files.createDirectories(dir);
            try (InputStream in = MyMod.class.getResourceAsStream("/mymod.properties")) {
                Files.copy(in, file);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /**
     * A random id for this server, kept in the config folder, so restarts count as the same machine.
     * Copying the folder to another server copies the id too; the license's machine limit still applies
     * to each id.
     */
    private String serverId() throws IOException {
        Path file = dir.resolve("mymod-server-id");
        if (Files.isRegularFile(file)) {
            String id = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!id.isEmpty()) {
                return id;
            }
        }
        Files.createDirectories(dir);
        String id = "fabric-" + UUID.randomUUID();
        Files.writeString(file, id, StandardCharsets.UTF_8);
        return id;
    }
}
