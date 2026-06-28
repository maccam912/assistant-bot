package com.assistantbot;

import com.assistantbot.command.AssistantCommand;
import com.assistantbot.gui.BotMenu;
import com.assistantbot.gui.BotRemoteItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AssistantMod implements ModInitializer {
    public static final String MOD_ID = "assistant-bot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Assistant Bot initializing...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AssistantCommand.register(dispatcher);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AssistantManager.getInstance().tick(server);
        });

        // Right-clicking the Bot Remote opens the menu (server-side only).
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClient()
                    && player instanceof ServerPlayerEntity serverPlayer
                    && BotRemoteItem.isRemote(player.getStackInHand(hand))) {
                BotMenu.open(serverPlayer);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AssistantManager.getInstance().removeAll();
        });

        LOGGER.info("Assistant Bot initialized!");
    }
}
