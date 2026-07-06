package com.assistantbot.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BotClientConnectionTest {
    @Test
    void hasChannelSoModsTreatBotAsLoggedIn() {
        BotClientConnection connection = new BotClientConnection();
        // Open Parties and Claims skips player-data setup (and later crashes the
        // server tick) for any player whose connection is still "connecting",
        // i.e. has a null channel.
        assertFalse(connection.isConnecting());
        assertTrue(connection.isConnected());
    }
}
