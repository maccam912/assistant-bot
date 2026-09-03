package com.assistantbot;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Boots Minecraft's registries once before any test class loads.
 *
 * <p>{@code BuiltInRegistries} throws from its static initialiser if it is first
 * touched before {@link Bootstrap#bootStrap()}, and a failed initialiser is
 * permanent for the JVM — so a single unlucky test ordering would fail every
 * later test with an unrelated {@code NoClassDefFoundError}. Registering this as
 * a {@code LauncherSessionListener} (see the services file beside it) removes the
 * ordering hazard entirely.
 */
public final class MinecraftTestBootstrap implements LauncherSessionListener {
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
