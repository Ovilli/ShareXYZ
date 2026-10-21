package de.ovilli.mixin;

import de.ovilli.ShareXYZ;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class PlayerFoodSyncMixin {
	@Unique private int sharexyz$foodBeforeUse;
	@Unique private float sharexyz$saturationBeforeUse;

	@Inject(method = "completeUsingItem", at = @At("HEAD"))
	private void sharexyz$rememberFoodBeforeUse(CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player) {
			sharexyz$foodBeforeUse = player.getFoodData().getFoodLevel();
			sharexyz$saturationBeforeUse = player.getFoodData().getSaturationLevel();
		}
	}

	@Inject(method = "completeUsingItem", at = @At("TAIL"))
	private void sharexyz$shareFoodAfterUse(CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player) {
			ShareXYZ.onPlayerAte(player, sharexyz$foodBeforeUse, sharexyz$saturationBeforeUse);
		}
	}
}
