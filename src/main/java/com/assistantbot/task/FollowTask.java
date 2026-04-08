package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Follow the bot's owner. Walks toward them when beyond FOLLOW_DISTANCE,
 * teleports if too far (>32 blocks). Fails if owner goes offline.
 */
public class FollowTask implements BotTask {
    private static final double FOLLOW_DISTANCE = 3.0;
    private static final double TOO_FAR_DISTANCE = 32.0;

    @Override
    public TickResult tick(AssistantBot bot) {
        ServerPlayerEntity owner = bot.getOwnerPlayer();
        if (owner == null) {
            return TickResult.FAILED;
        }

        Vec3d botPos = bot.getPos();
        Vec3d ownerPos = owner.getEntityPos();
        double distance = botPos.distanceTo(ownerPos);

        if (distance > TOO_FAR_DISTANCE) {
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            NavigationHelper.teleportNear(bot, ownerPos);
            return TickResult.CONTINUE;
        }

        if (distance <= FOLLOW_DISTANCE) {
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            LookHelper.lookAt(bot.getFakePlayer(), ownerPos.add(0, owner.getStandingEyeHeight(), 0));
            return TickResult.CONTINUE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), ownerPos.add(0, owner.getStandingEyeHeight(), 0));
        NavigationHelper.navigateTo(bot, ownerPos, NavigationHelper.WALK_SPEED);
        return TickResult.CONTINUE;
    }

    @Override
    public String getStatusString() { return "following owner"; }
}
