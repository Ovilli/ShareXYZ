package de.ovilli;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record AxisRolePayload(byte axis, String xName, String yName, String zName, boolean paused)
		implements CustomPacketPayload {
	private static final int MAX_NAME_LENGTH = 16;

	public AxisRolePayload {
		if (axis < -1 || axis > 2) throw new IllegalArgumentException("Invalid ShareXYZ axis: " + axis);
		xName = boundedName(xName);
		yName = boundedName(yName);
		zName = boundedName(zName);
	}

	private static String boundedName(String name) {
		Objects.requireNonNull(name, "controller name");
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("ShareXYZ controller names must be at most " + MAX_NAME_LENGTH + " characters");
		}
		return name;
	}

	public static final Type<AxisRolePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ShareXYZ.MOD_ID, "axis_role"));
	public static final StreamCodec<FriendlyByteBuf, AxisRolePayload> CODEC = CustomPacketPayload.codec(
		(payload, buffer) -> {
			buffer.writeByte(payload.axis());
			buffer.writeUtf(payload.xName(), MAX_NAME_LENGTH);
			buffer.writeUtf(payload.yName(), MAX_NAME_LENGTH);
			buffer.writeUtf(payload.zName(), MAX_NAME_LENGTH);
			buffer.writeBoolean(payload.paused());
		},
		buffer -> new AxisRolePayload(
			buffer.readByte(),
			buffer.readUtf(MAX_NAME_LENGTH),
			buffer.readUtf(MAX_NAME_LENGTH),
			buffer.readUtf(MAX_NAME_LENGTH),
			buffer.readBoolean()
		)
	);

	@Override
	public Type<AxisRolePayload> type() {
		return TYPE;
	}
}
