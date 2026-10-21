package de.ovilli.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class SharedPlayerCollisionMixin {
	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void sharexyz$preventPlayerPushing(Entity other, CallbackInfo ci) {
		if ((Object) this instanceof Player && other instanceof Player) ci.cancel();
	}

	@Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
	private void sharexyz$ignorePlayerCollision(Entity other, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player && other instanceof Player) cir.setReturnValue(false);
	}

	@Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
	private void sharexyz$ignorePlayerAsCollisionTarget(Entity other, CallbackInfoReturnable<Boolean> cir) {
		// Block placement asks whether an entity collides with a shape using a null
		// source entity. Stacked shared-avatar players must not obstruct that check.
		if ((Object) this instanceof Player && (other == null || other instanceof Player)) {
			cir.setReturnValue(false);
		}
	}
}
