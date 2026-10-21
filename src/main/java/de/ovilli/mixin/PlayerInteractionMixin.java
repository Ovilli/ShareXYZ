package de.ovilli.mixin;

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

@Mixin(Player.class)
public abstract class PlayerInteractionMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void sharexyz$disablePlayerAttacks(Entity target, CallbackInfo ci) {
		if (target instanceof Player) ci.cancel();
	}

	@Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
	private void sharexyz$disablePlayerInteraction(Entity target, InteractionHand hand, Vec3 hitPosition,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (target instanceof Player) cir.setReturnValue(InteractionResult.PASS);
	}
}
