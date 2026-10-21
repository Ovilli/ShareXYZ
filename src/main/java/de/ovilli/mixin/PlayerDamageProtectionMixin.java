package de.ovilli.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PlayerDamageProtectionMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void sharexyz$blockPlayerCausedDamage(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player && source.getEntity() instanceof Player attacker && attacker != (Object) this) {
			cir.setReturnValue(false);
		}
	}
}
