package com.assistantbot.bot;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

/**
 * A no-op ClientConnection for the bot player. All outbound packets are
 * silently dropped. Uses an EmbeddedChannel so isOpen() returns true
 * (required for ender pearls and other mechanics that check connection state).
 *
 * Follows the Carpet mod's FakeClientConnection pattern.
 */
public class BotClientConnection extends Connection {

    public BotClientConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        // Drop all outbound packets silently
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> state, T packetListener) {
    }
}
