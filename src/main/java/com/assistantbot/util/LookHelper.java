package com.assistantbot.util;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Set the fake player's yaw/pitch to face a world position.
 */
public final class LookHelper {
    private LookHelper() {}

    public static void lookAt(FakePlayer player, Vec3d target) {
        Vec3d eyePos = player.getEyePos();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // MC yaw: 0=south(+Z), 90=west(-X), 180=north(-Z), -90=east(+X)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
        player.setHeadYaw(yaw);
    }
}
