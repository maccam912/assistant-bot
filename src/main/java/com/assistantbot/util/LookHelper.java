package com.assistantbot.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Set the fake player's yaw/pitch to face a world position.
 */
public final class LookHelper {
    private LookHelper() {}

    public static void lookAt(ServerPlayer player, Vec3 target) {
        Vec3 eyePos = player.getEyePosition();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // MC yaw: 0=south(+Z), 90=west(-X), 180=north(-Z), -90=east(+X)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        player.setYRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
        player.setYHeadRot(yaw);
    }
}
