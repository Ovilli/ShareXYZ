package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the client from fighting the server over the position of the shared avatar.
 *
 * <p>The raw key presses must not be filtered: they are the only input the server ever
 * sees ({@code ServerboundPlayerInputPacket} carries them verbatim), so the server can
 * translate them into per-axis intents.
 *
 * <p>The movement vector on the other hand is only used for the local prediction. While
 * the player rides the shared avatar the server owns the position, so the client stops
 * walking on its own.
 */
@Mixin(ClientInput.class)
public abstract class AxisInputMixin {
	@Inject(method = "getMoveVector", at = @At("HEAD"), cancellable = true)
	private void sharexyz$neutraliseLocalMovement(CallbackInfoReturnable<Vec2> cir) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		if (!(client.player.getVehicle() instanceof SharedAvatarEntity)) return;
		cir.setReturnValue(Vec2.ZERO);
	}
}
