package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Participants can never leave the shared avatar.
 *
 * <p>{@code Player#wantsToStopRiding()} returns {@code isShiftKeyDown()}, and
 * {@code Player#rideTick()} dismounts the player on the server as soon as that is the case.
 * Shift keeps its normal sneak behavior while the player remains mounted, so without this
 * mixin the whole group would fall off the avatar every time somebody pressed sneak.
 */
@Mixin(Player.class)
public abstract class PlayerNoDismountMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void sharexyz$stayOnSharedAvatar(CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		// This must apply on both logical sides. Otherwise the client locally
		// dismounts on Shift before the server rejects the dismount and resyncs it.
		if (self.getVehicle() instanceof SharedAvatarEntity) {
			cir.setReturnValue(false);
		}
	}
}
