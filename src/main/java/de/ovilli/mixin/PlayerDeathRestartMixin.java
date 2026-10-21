package de.ovilli.mixin;

import de.ovilli.ShareXYZ;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class PlayerDeathRestartMixin {
	@Inject(method = "die", at = @At("TAIL"))
	private void sharexyz$restartGroupOnPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player) {
			ShareXYZ.onPlayerDeath(player);
		}
	}
}
