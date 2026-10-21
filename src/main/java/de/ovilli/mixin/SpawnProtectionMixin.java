package de.ovilli.mixin;

import de.ovilli.ShareXYZ;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class SpawnProtectionMixin {
	@Inject(method = "isUnderSpawnProtection", at = @At("HEAD"), cancellable = true)
	private void sharexyz$allowParticipantsToBuildAtSpawn(ServerLevel level, BlockPos pos, Player player,
			CallbackInfoReturnable<Boolean> cir) {
		if (player instanceof ServerPlayer serverPlayer && ShareXYZ.isChallengeParticipant(serverPlayer.getUUID())) {
			cir.setReturnValue(false);
		}
	}
}
