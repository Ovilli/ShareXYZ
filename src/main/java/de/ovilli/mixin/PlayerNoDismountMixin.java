package de.ovilli.mixin;

import de.ovilli.ShareXYZ;
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
 * The Y controller needs the sneak key to fly downwards, so without this mixin the whole
 * group would fall off the avatar every time somebody pressed sneak.
 */
@Mixin(Player.class)
public abstract class PlayerNoDismountMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void sharexyz$stayOnSharedAvatar(CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (self.level() == null || self.level().isClientSide()) return;
		if (self.getVehicle() instanceof SharedAvatarEntity
				&& ShareXYZ.isChallengeParticipant(self.getUUID())) {
			cir.setReturnValue(false);
		}
	}
}
