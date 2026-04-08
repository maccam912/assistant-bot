package com.assistantbot.bot;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import org.jetbrains.annotations.Nullable;

/**
 * A no-op ClientConnection for the bot player. All outbound packets are
 * silently dropped. Uses an EmbeddedChannel so isOpen() returns true
 * (required for ender pearls and other mechanics that check connection state).
 *
 * Follows the Carpet mod's FakeClientConnection pattern.
 */
public class BotClientConnection extends ClientConnection {

    public BotClientConnection() {
        super(NetworkSide.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        // Drop all outbound packets silently
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setInitialPacketListener(PacketListener packetListener) {
    }

    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
    }
}
