package de.ovilli.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class HideOtherPlayersMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void sharexyz$hideOtherPlayers(E entity, Frustum frustum,
			double cameraX, double cameraY, double cameraZ, float partialTick,
			CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof Player && entity != Minecraft.getInstance().player) {
			cir.setReturnValue(false);
		}
	}
}
