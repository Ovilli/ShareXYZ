package de.ovilli;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AxisRolePayload(byte axis) implements CustomPacketPayload {
	public static final Type<AxisRolePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ShareXYZ.MOD_ID, "axis_role"));
	public static final StreamCodec<FriendlyByteBuf, AxisRolePayload> CODEC = CustomPacketPayload.codec(
		(payload, buffer) -> buffer.writeByte(payload.axis()),
		buffer -> new AxisRolePayload(buffer.readByte())
	);

	@Override
	public Type<AxisRolePayload> type() {
		return TYPE;
	}
}
