package de.ovilli;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.resources.Identifier;

public class ShareXYZClient implements ClientModInitializer {
	private static volatile AxisRolePayload hudState;

	@Override
	public void onInitializeClient() {
		// The shared avatar is invisible, but it still needs a renderer on the client.
		EntityRenderers.register(SharedAvatarEntity.TYPE, NoopRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(AxisRolePayload.TYPE, (payload, context) ->
			context.client().execute(() -> hudState = payload));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(ShareXYZ.MOD_ID, "role_hud"), ShareXYZClient::renderRoleHud);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetControlledAxis());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetControlledAxis());
	}

	public static byte controlledAxis() {
		return hudState == null ? -2 : hudState.axis();
	}

	public static void resetControlledAxis() {
		hudState = null;
	}

	private static void renderRoleHud(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
		AxisRolePayload state = hudState;
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.player == null || client.level == null) return;

		int x = 12;
		int y = 12;
		int width = 210;
		int height = 62;
		int[] axisColors = {0xFFFF7777, 0xFF72DDA3, 0xFF72CFFF};
		String[] names = {state.xName(), state.yName(), state.zName()};
		String[] keys = {"A/D", "SPACE", "W/S"};

		// A single subtle background keeps the compact text readable; the layout has no rules or boxes.
		graphics.fill(x, y, x + width, y + height, 0xA8000000);

		graphics.text(client.font, "SHAREXYZ", x + 10, y + 8, 0xFFF1F5F8);
		String status = state.paused() ? "PAUSED" : "LIVE";
		graphics.text(client.font, status, x + width - 10 - client.font.width(status), y + 8,
			state.paused() ? 0xFFFFD477 : 0xFF79E4BD);

		for (int i = 0; i < names.length; i++) {
			int rowY = y + 22 + i * 12;
			boolean mine = state.axis() == i;
			if (mine) graphics.text(client.font, ">", x + 8, rowY, axisColors[i]);
			graphics.text(client.font, "XYZ".substring(i, i + 1), x + 17, rowY,
				mine ? axisColors[i] : 0xFF9AA7B4);

			int keyX = x + width - 10 - client.font.width(keys[i]);
			int nameX = x + 29;
			String name = fitText(names[i], keyX - nameX - 8, client);
			graphics.text(client.font, name, nameX, rowY, mine ? 0xFFFFFFFF : 0xFFD0D6DC);
			graphics.text(client.font, keys[i], keyX, rowY, 0xFF9AA7B4);
		}
	}

	private static String fitText(String text, int maxWidth, Minecraft client) {
		if (client.font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		if (maxWidth <= client.font.width(ellipsis)) return "";
		return client.font.plainSubstrByWidth(text, maxWidth - client.font.width(ellipsis)) + ellipsis;
	}
}
