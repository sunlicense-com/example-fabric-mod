package com.example.mymod;

import net.minecraft.server.MinecraftServer;

/**
 * The mod's features, started once the license is valid. The implementation lives in the {@code core} package, which
 * release builds ship sealed (see {@code license/SealedCore}): keep this interface small and outside it.
 */
public interface Features {
    void start(MinecraftServer server);
}
