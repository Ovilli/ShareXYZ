package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla "Press Shift to dismount" hint.
 *
 * <p>The participants can never leave the shared avatar. Telling the player that sneak
 * dismounts them would be wrong, so the overlay message is suppressed while riding it.
 */
@Mixin(Hud.class)
public abstract class SharedAvatarMountMessageMixin {
	@Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
	private void sharexyz$hideDismountHint(Component message, boolean fade, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		if (!(client.player.getVehicle() instanceof SharedAvatarEntity)) return;
		ComponentContents contents = message.getContents();
		if (contents instanceof TranslatableContents translatable) {
			String key = translatable.getKey();
			if ("mount.onboard".equals(key) || "mount.sit".equals(key)) {
				ci.cancel();
			}
		}
	}
}
