package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Participants never take fall damage on their own.
 *
 * <p>The fall of the shared avatar is a group event: the server measures the drop height
 * of the carrier entity and applies the damage to every participant at once. Without this
 * mixin every rider would additionally take the vanilla fall damage of their own
 * (meaningless) movement, and the carrier would push its fall damage down onto the
 * passengers a second time.
 */
@Mixin(LivingEntity.class)
public abstract class SharedAvatarFallDamageMixin {
	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void sharexyz$noAutomaticFallDamage(double y, float fallDistance, DamageSource source,
			CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (self.getVehicle() instanceof SharedAvatarEntity) {
			cir.setReturnValue(false);
		}
	}
}
