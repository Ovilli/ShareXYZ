package de.ovilli.mixin;

import de.ovilli.SharedAvatarEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Allows the shared avatar to be used as a vehicle.
 *
 * <p>Vanilla refuses to mount anybody on a vehicle whose entity type cannot be serialised
 * ({@code EntityType#canSerialize()}), because a vehicle that is never written to the world
 * save would lose its passengers when the world is reloaded. The shared avatar is
 * deliberately transient - it is created together with the group and discarded with it - so
 * this check has to be bypassed for it.
 *
 * <p>Without the bypass {@code Entity#startRiding} returns {@code false} on the server, the
 * participants never become passengers, and the whole model collapses: the server keeps
 * accepting the position the client reports (so everybody walks around on their own) and
 * the shared fall - and with it the group fall damage - never happens.
 */
@Mixin(Entity.class)
public abstract class EntityStartRidingMixin {
	@Redirect(
		method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z")
	)
	private boolean sharexyz$allowSharedAvatarAsVehicle(EntityType<?> vehicleType) {
		return vehicleType == SharedAvatarEntity.TYPE || vehicleType.canSerialize();
	}
}
