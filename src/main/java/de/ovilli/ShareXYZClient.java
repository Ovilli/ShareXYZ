package de.ovilli;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;

public class ShareXYZClient implements ClientModInitializer {
	private static volatile byte controlledAxis = -2;

	@Override
	public void onInitializeClient() {
		// The shared avatar is invisible, but it still needs a renderer on the client.
		EntityRenderers.register(SharedAvatarEntity.TYPE, NoopRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(AxisRolePayload.TYPE, (payload, context) ->
			context.client().execute(() -> controlledAxis = payload.axis()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetControlledAxis());
	}

	public static byte controlledAxis() {
		return controlledAxis;
	}

	public static void resetControlledAxis() {
		controlledAxis = -2;
	}
}
