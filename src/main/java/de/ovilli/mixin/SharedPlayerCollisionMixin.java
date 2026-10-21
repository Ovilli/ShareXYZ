package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The participants share one hitbox, so they must not push or collide with each other.
 * Players who are not riding the same shared avatar keep their normal collision.
 */
@Mixin(Entity.class)
public abstract class SharedPlayerCollisionMixin {
	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void sharexyz$preventPlayerPushing(Entity other, CallbackInfo ci) {
		if (sharexyz$shareAvatar((Entity) (Object) this, other)) ci.cancel();
	}

	@Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
	private void sharexyz$ignorePlayerCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
		if (sharexyz$shareAvatar((Entity) (Object) this, other)) cir.setReturnValue(false);
	}

	@Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
	private void sharexyz$ignorePlayerAsCollisionTarget(Entity other, CallbackInfoReturnable<Boolean> cir) {
		// Block placement asks whether an entity collides with a shape using a null
		// source entity. Stacked shared-avatar players must not obstruct that check.
		Entity self = (Entity) (Object) this;
		if (self instanceof Player player
				&& player.getVehicle() instanceof SharedAvatarEntity
				&& (other == null || other instanceof Player)) {
			cir.setReturnValue(false);
		}
	}

	private static boolean sharexyz$shareAvatar(Entity self, Entity other) {
		if (!(self instanceof Player) || !(other instanceof Player)) return false;
		Entity vehicle = self.getVehicle();
		return vehicle instanceof SharedAvatarEntity && vehicle == other.getVehicle();
	}
}
