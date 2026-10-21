package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Players riding the shared avatar are stacked in the exact same spot. Rendering them would
 * only produce z-fighting and a pile of skins in the camera, so the other riders are hidden.
 * Players who are not part of the shared body stay visible.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class HideOtherPlayersMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void sharexyz$hideOtherPlayers(E entity, Frustum frustum,
			double cameraX, double cameraY, double cameraZ, float partialTick,
			CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof Player && entity != Minecraft.getInstance().player
				&& entity.getVehicle() instanceof SharedAvatarEntity) {
			cir.setReturnValue(false);
		}
	}
}
