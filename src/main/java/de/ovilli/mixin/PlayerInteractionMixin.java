package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Participants ride the same body and are at the same position, so hitting or using one of
 * them would always hit a teammate. Only players on the same shared avatar are protected;
 * every other player can still be attacked and interacted with normally.
 */
@Mixin(Player.class)
public abstract class PlayerInteractionMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void sharexyz$disablePlayerAttacks(Entity target, CallbackInfo ci) {
		if (sharexyz$onSameAvatar((Player) (Object) this, target)) ci.cancel();
	}

	@Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
	private void sharexyz$disablePlayerInteraction(Entity target, InteractionHand hand, Vec3 hitPosition,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (sharexyz$onSameAvatar((Player) (Object) this, target)) cir.setReturnValue(InteractionResult.PASS);
	}

	private static boolean sharexyz$onSameAvatar(Player self, Entity target) {
		if (!(target instanceof Player)) return false;
		Entity vehicle = self.getVehicle();
		return vehicle instanceof SharedAvatarEntity && vehicle == target.getVehicle();
	}
}
