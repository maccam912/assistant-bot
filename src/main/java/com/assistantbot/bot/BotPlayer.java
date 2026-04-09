package com.assistantbot.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalInt;
import java.util.Set;

/**
 * A visible bot player entity that appears in the world like a real player.
 * Unlike Fabric API's FakePlayer (which is invisible and server-only), this
 * follows the Carpet mod pattern: extends ServerPlayerEntity and is registered
 * via PlayerManager.onPlayerConnect() so the server sends spawn packets to
 * all real clients.
 */
public class BotPlayer extends ServerPlayerEntity {

    private final MinecraftServer mcServer;

    /**
     * Desired horizontal velocity, set by NavigationHelper at ~4Hz (task tick rate).
     * Re-applied every tick so movement is smooth between task updates.
     * Null means no movement desired (bot is idle or stationary).
     */
    private Vec3d desiredHorizontalVelocity = null;

    /** Callback invoked when damage would have been lethal. Set by AssistantBot. */
    private Runnable onLethalDamageCallback = null;

    public void setOnLethalDamageCallback(Runnable callback) {
        this.onLethalDamageCallback = callback;
    }

    public BotPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile, SyncedClientOptions.createDefault());
        this.mcServer = server;
    }

    /**
     * Set the desired horizontal velocity. This will be re-applied every tick
     * to produce smooth movement. Set to null to stop horizontal movement.
     */
    public void setDesiredHorizontalVelocity(@Nullable Vec3d velocity) {
        this.desiredHorizontalVelocity = velocity;
    }

    public @Nullable Vec3d getDesiredHorizontalVelocity() {
        return this.desiredHorizontalVelocity;
    }

    /**
     * Spawn this bot into the world, making it visible to all players.
     * This registers the bot with the player list and entity tracker.
     */
    public void spawn(double x, double y, double z, float yaw, float pitch) {
        ServerWorld world = (ServerWorld) this.getEntityWorld();
        PlayerManager playerManager = mcServer.getPlayerManager();

        BotClientConnection connection = new BotClientConnection();
        ConnectedClientData clientData = ConnectedClientData.createDefault(this.getGameProfile(), false);

        // This is the key call: it registers the player with the server,
        // sends spawn packets to all clients, and sets up entity tracking.
        playerManager.onPlayerConnect(connection, this, clientData);

        // Position the bot after connection setup
        this.teleport(world, x, y, z, Set.of(), yaw, pitch, true);
        this.setHealth(20.0f);
        this.changeGameMode(GameMode.SURVIVAL);

        // Broadcast head rotation and position to all clients
        playerManager.sendToAll(
                new EntitySetHeadYawS2CPacket(this, (byte) (this.headYaw * 256 / 360)));
        playerManager.sendToAll(
                EntityPositionSyncS2CPacket.create(this));

        // Show all skin layers (bitmask 0x7f = all 7 model parts visible)
        this.getDataTracker().set(PLAYER_MODE_CUSTOMIZATION_ID, (byte) 0x7f);
    }

    /**
     * Remove this bot from the server properly.
     */
    public void despawn() {
        mcServer.getPlayerManager().remove(this);
    }

    // --- Override tick to keep chunk tracking updated and apply movement ---

    @Override
    public void tick() {
        if (mcServer.getTicks() % 10 == 0) {
            this.networkHandler.syncWithPlayerPosition();
            ((ServerWorld) this.getEntityWorld()).getChunkManager().updatePosition(this);
        }

        // Re-apply desired horizontal velocity every tick so movement is smooth.
        // Vertical velocity (gravity, jumps) is managed by entity physics.
        if (desiredHorizontalVelocity != null) {
            Vec3d currentVel = this.getVelocity();
            this.setVelocity(desiredHorizontalVelocity.x, currentVel.y, desiredHorizontalVelocity.z);
        }

        super.tick();
        this.playerTick();
    }

    // --- Invincibility: clamp damage so bot never dies ---

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        float currentHealth = this.getHealth();

        // Clamp damage so health never reaches 0 — prevents
        // LivingEntity.damage() from triggering onDeath() internally.
        float maxAllowable = currentHealth - 1.0f;
        if (amount >= maxAllowable) {
            boolean wasLethal = (amount >= currentHealth);
            amount = Math.max(0, maxAllowable); // leaves us at 1 HP
            boolean result = super.damage(world, source, amount);
            if (wasLethal && onLethalDamageCallback != null) {
                onLethalDamageCallback.run();
            }
            return result;
        }

        return super.damage(world, source, amount);
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        // Never allow death — reset health and cancel.
        // This is a safety net for damage sources that bypass damage()
        // (e.g., void damage, /kill command).
        this.setHealth(1.0f);
        this.deathTime = 0;
        this.dead = false;
    }

    // --- Safety overrides (like Carpet mod) ---

    @Override
    public void openEditSignScreen(net.minecraft.block.entity.SignBlockEntity sign, boolean front) {
    }

    @Override
    public OptionalInt openHandledScreen(@Nullable NamedScreenHandlerFactory factory) {
        return OptionalInt.empty();
    }

    @Override
    public void openHorseInventory(AbstractHorseEntity horse, Inventory inventory) {
    }
}
