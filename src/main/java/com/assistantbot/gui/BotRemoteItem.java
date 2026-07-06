package com.assistantbot.gui;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The "Bot Remote" — a vanilla compass stamped with a {@code custom_data} marker
 * and a custom name. Using a vanilla item (instead of a registered custom item)
 * means it renders correctly on a vanilla client; this mod is server-side only.
 *
 * Right-clicking the remote opens the {@link BotMenu} (wired in AssistantMod via
 * UseItemCallback).
 */
public final class BotRemoteItem {
    /** Marker key stored in the item's custom_data component. */
    private static final String MARKER_KEY = "assistant_remote";

    private BotRemoteItem() {}

    /** Creates a fresh Bot Remote item. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.COMPASS);

        CompoundTag nbt = new CompoundTag();
        nbt.putBoolean(MARKER_KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Bot Remote").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));

        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to open the bot menu")
                        .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));

        return stack;
    }

    /** True if the given stack is a Bot Remote. */
    public static boolean isRemote(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(MARKER_KEY);
    }

    /** Gives the player a remote if they don't already have one in their inventory. */
    public static void giveTo(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isRemote(inv.getItem(i))) {
                return; // already has one
            }
        }
        if (!player.addItem(create())) {
            player.drop(create(), false);
        }
    }
}
