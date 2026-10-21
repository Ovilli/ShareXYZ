package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders participants on the shared avatar upright and uses their movement based walk
 * animation, even though vanilla treats them as passengers.
 */
@Mixin(AvatarRenderer.class)
public abstract class SharedAvatarPlayerRenderMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL")
	)
	private void sharexyz$standAndWalkOnSharedAvatar(Avatar entity, AvatarRenderState state,
											 float partialTick, CallbackInfo ci) {
		if (!(entity instanceof Player player)
				|| !(player.getVehicle() instanceof SharedAvatarEntity)) {
			return;
		}

		// HumanoidModel uses this flag to bend the legs and arms into a seated riding pose.
		state.isPassenger = false;
		// LivingEntityRenderer clears these values for passengers, so restore the walk cycle
		// that SharedAvatarWalkAnimationMixin keeps updating from the player's movement.
		state.walkAnimationPos = player.walkAnimation.position(partialTick);
		state.walkAnimationSpeed = player.walkAnimation.speed(partialTick);
	}
}
