package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the normal movement based walk animation running for participants on the shared
 * avatar. Vanilla stops that animation for every passenger.
 */
@Mixin(LivingEntity.class)
public abstract class SharedAvatarWalkAnimationMixin {
	@Redirect(
		method = "calculateEntityAnimation(Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/LivingEntity;isPassenger()Z"
		)
	)
	private boolean sharexyz$animateWhileRidingSharedAvatar(LivingEntity entity) {
		return !(entity.getVehicle() instanceof SharedAvatarEntity) && entity.isPassenger();
	}
}
