package de.ovilli.mixin;

import de.ovilli.ShareXYZ;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the death of a participant.
 *
 * <p>The hook has to sit on {@link ServerPlayer#die(DamageSource)}: the server side player
 * replaces the whole death handling of its superclass and never calls
 * {@code LivingEntity#die(DamageSource)}, so an injection into the living entity would never
 * fire for a player.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerDeathRestartMixin {
	@Inject(method = "die", at = @At("TAIL"))
	private void sharexyz$restartGroupOnPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
		ShareXYZ.onPlayerDeath((ServerPlayer) (Object) this, damageSource);
	}
}
