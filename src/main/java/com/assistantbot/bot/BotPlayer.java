package com.assistantbot.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalInt;
import java.util.Set;

/**
 * A visible bot player entity that appears in the world like a real player.
 * Unlike Fabric API's FakePlayer (which is invisible and server-only), this
 * follows the Carpet mod pattern: extends ServerPlayer and is registered
 * via PlayerList.placeNewPlayer() so the server sends spawn packets to
 * all real clients.
 */
public class BotPlayer extends ServerPlayer {

    private final MinecraftServer mcServer;

    /**
     * Desired horizontal velocity, set by NavigationHelper at ~4Hz (task tick rate).
     * Re-applied every tick so movement is smooth between task updates.
     * Null means no movement desired (bot is idle or stationary).
     */
    private Vec3 desiredHorizontalVelocity = null;

    /** Callback invoked when damage would have been lethal. Set by AssistantBot. */
    private Runnable onLethalDamageCallback = null;

    public void setOnLethalDamageCallback(Runnable callback) {
        this.onLethalDamageCallback = callback;
    }

    public BotPlayer(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());
        this.mcServer = server;
    }

    /**
     * Set the desired horizontal velocity. This will be re-applied every tick
     * to produce smooth movement. Set to null to stop horizontal movement.
     */
    public void setDesiredHorizontalVelocity(@Nullable Vec3 velocity) {
        this.desiredHorizontalVelocity = velocity;
    }

    public @Nullable Vec3 getDesiredHorizontalVelocity() {
        return this.desiredHorizontalVelocity;
    }

    /**
     * Spawn this bot into the world, making it visible to all players.
     * This registers the bot with the player list and entity tracker.
     */
    public void spawn(double x, double y, double z, float yaw, float pitch) {
        ServerLevel world = (ServerLevel) this.level();
        PlayerList playerManager = mcServer.getPlayerList();

        BotClientConnection connection = new BotClientConnection();
        CommonListenerCookie clientData = CommonListenerCookie.createInitial(this.getGameProfile(), false);

        // This is the key call: it registers the player with the server,
        // sends spawn packets to all clients, and sets up entity tracking.
        playerManager.placeNewPlayer(connection, this, clientData);

        // Position the bot after connection setup
        this.teleportTo(world, x, y, z, Set.of(), yaw, pitch, true);
        this.setHealth(20.0f);
        this.setGameMode(GameType.SURVIVAL);

        // Broadcast head rotation and position to all clients
        playerManager.broadcastAll(
                new ClientboundRotateHeadPacket(this, (byte) (this.yHeadRot * 256 / 360)));
        playerManager.broadcastAll(
                ClientboundEntityPositionSyncPacket.of(this));

        // Show all skin layers (bitmask 0x7f = all 7 model parts visible)
        this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
    }

    /**
     * Remove this bot from the server properly.
     */
    public void despawn() {
        mcServer.getPlayerList().remove(this);
    }

    // --- Override tick to keep chunk tracking updated and apply movement ---

    @Override
    public void tick() {
        if (mcServer.getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            ((ServerLevel) this.level()).getChunkSource().move(this);
        }

        // Re-apply desired horizontal velocity every tick so movement is smooth.
        // Vertical velocity (gravity, jumps) is managed by entity physics.
        if (desiredHorizontalVelocity != null) {
            Vec3 currentVel = this.getDeltaMovement();
            this.setDeltaMovement(desiredHorizontalVelocity.x, currentVel.y, desiredHorizontalVelocity.z);
        }

        super.tick();
        this.doTick();
    }

    // --- Invincibility: clamp damage so bot never dies ---

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        float currentHealth = this.getHealth();

        // Clamp damage so health never reaches 0 — prevents
        // LivingEntity.damage() from triggering onDeath() internally.
        float maxAllowable = currentHealth - 1.0f;
        if (amount >= maxAllowable) {
            boolean wasLethal = (amount >= currentHealth);
            amount = Math.max(0, maxAllowable); // leaves us at 1 HP
            boolean result = super.hurtServer(world, source, amount);
            if (wasLethal && onLethalDamageCallback != null) {
                onLethalDamageCallback.run();
            }
            return result;
        }

        return super.hurtServer(world, source, amount);
    }

    @Override
    public void die(DamageSource damageSource) {
        // Never allow death — reset health and cancel.
        // This is a safety net for damage sources that bypass damage()
        // (e.g., void damage, /kill command).
        this.setHealth(1.0f);
        this.deathTime = 0;
        this.dead = false;
    }

    // --- Safety overrides (like Carpet mod) ---

    @Override
    public void openTextEdit(net.minecraft.world.level.block.entity.SignBlockEntity sign, boolean front) {
    }

    @Override
    public OptionalInt openMenu(@Nullable MenuProvider factory) {
        return OptionalInt.empty();
    }

    @Override
    public void openHorseInventory(AbstractHorse horse, Container inventory) {
    }
}
