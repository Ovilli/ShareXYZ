package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Riders of the shared avatar occupy the same spot, so the crosshair must not pick a
 * teammate instead of the block or entity in front of the group. Other players are still
 * pickable.
 */
@Mixin(Entity.class)
public abstract class IgnoreOtherPlayersTargetMixin {
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void sharexyz$ignoreOtherPlayersForTargeting(CallbackInfoReturnable<Boolean> cir) {
		Entity entity = (Entity) (Object) this;
		if (entity instanceof Player && entity != Minecraft.getInstance().player
				&& entity.getVehicle() instanceof SharedAvatarEntity) {
			cir.setReturnValue(false);
		}
	}
}
