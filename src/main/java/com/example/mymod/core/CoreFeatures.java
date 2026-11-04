package com.example.mymod.core;

import net.minecraft.server.MinecraftServer;

import com.example.mymod.Features;
import com.example.mymod.MyMod;

/**
 * Your mod's features. Fabric events can be registered here, at start-up of the features; make each one return early
 * unless {@link MyMod#active()}, since Fabric can't unload a mod. Everything in this package is the "core": release
 * builds encrypt it, and only a valid license unlocks it (see {@code license/SealedCore}). Mixins and the entrypoint
 * named in fabric.mod.json must stay outside the core, because Fabric loads them by name before any license check.
 */
public final class CoreFeatures implements Features {
    @Override
    public void start(MinecraftServer server) {
        // ServerTickEvents.END_SERVER_TICK.register(s -> { if (!MyMod.active()) return; ... });
        MyMod.LOG.info("MyMod is running.");
    }
}
