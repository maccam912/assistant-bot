package com.assistantbot.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

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

        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(MARKER_KEY, true);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));

        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("Bot Remote").styled(s -> s.withItalic(false).withColor(Formatting.GOLD)));

        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("Right-click to open the bot menu")
                        .styled(s -> s.withItalic(false).withColor(Formatting.GRAY)))));

        return stack;
    }

    /** True if the given stack is a Bot Remote. */
    public static boolean isRemote(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.copyNbt().contains(MARKER_KEY);
    }

    /** Gives the player a remote if they don't already have one in their inventory. */
    public static void giveTo(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (isRemote(inv.getStack(i))) {
                return; // already has one
            }
        }
        if (!player.giveItemStack(create())) {
            player.dropItem(create(), false);
        }
    }
}
