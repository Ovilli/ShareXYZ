package de.ovilli;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ShareXYZ - three players, one body, one axis each.
 *
 * <p>Movement model
 * ----------------
 * The shared body is a dedicated server-side entity ({@link SharedAvatarEntity}) that every
 * participant rides. That gives us a single, server-owned position:
 * <ul>
 *   <li>the server integrates the three axis intents and moves the carrier entity,</li>
 *   <li>the server mounts the participants, so their position is derived from the carrier,</li>
 *   <li>vanilla ignores the position a passenger reports, so no client can fight the server.</li>
 * </ul>
 *
 * <p>Input model
 * -------------
 * The client no longer filters its key presses. It sends the raw vanilla
 * {@link Input} record, the server reads it through {@code ServerPlayer#getLastClientInput()}
 * and turns it into a per-axis intent. Because the intents are world-axis aligned (A/D
 * drive X, W/S drive Z, Space/Shift drive Y) they never degenerate: there is no camera
 * yaw for which a key combination stops working.
 */
public class ShareXYZ implements ModInitializer {
	public static final String MOD_ID = "sharexyz";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Walking speed in blocks per tick (4.317 m/s). */
	private static final double WALK_SPEED = 0.2159;
	/** Sprinting multiplies the walking speed by 1.3 (5.612 m/s). */
	private static final double SPRINT_MULTIPLIER = 1.3;
	/** How quickly the horizontal velocity reaches its target. */
	private static final double HORIZONTAL_ACCELERATION = 0.3;
	/** Horizontal velocity damping while no input is given. */
	private static final double HORIZONTAL_FRICTION = 0.91;
	/** Gravity in blocks per tick^2. */
	private static final double VERTICAL_GRAVITY = 0.08;
	/** Terminal velocity in blocks per tick. */
	private static final double TERMINAL_VELOCITY = 3.92;
	/** Ascending speed while the Y controller holds jump. */
	private static final double RISE_SPEED = 0.42;
	/** Descending speed while the Y controller holds sneak. */
	private static final double DESCEND_SPEED = 0.42;
	/** A fall has to be longer than this to deal damage. */
	private static final double FALL_DAMAGE_THRESHOLD = 3.0;

	private static final long HUD_UPDATE_INTERVAL = 20;

	private static final Map<Axis, UUID> CONTROLLERS = new EnumMap<>(Axis.class);
	/** Dimension each controller was last seen in. Used to detect a controller that changed dimension. */
	private static final Map<UUID, ResourceKey<Level>> CONTROLLER_DIMENSIONS = new HashMap<>();
	/** Players that still need a one-off synchronisation of the shared needs. */
	private static final Set<UUID> NEEDS_NEEDS_SYNC = new HashSet<>();
	private static final Set<UUID> PARTICIPANTS = new HashSet<>();
	private static final Set<UUID> WAITING_FOR_RESPAWN = new HashSet<>();
	private static final List<ServerPlayer> LIVING_PLAYERS = new ArrayList<>(3);
	private static final Map<UUID, ServerPlayer> PLAYERS_BY_ID = new HashMap<>(3);

	private static SharedAvatar sharedAvatar;
	private static boolean restarting;
	private static boolean applyingGroupFallDamage;
	private static ServerPlayer pendingGroupFallRestart;
	private static long tickCounter;

	@Override
	public void onInitialize() {
		SharedAvatarEntity.register();
		PayloadTypeRegistry.clientboundPlay().register(AxisRolePayload.TYPE, AxisRolePayload.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			PARTICIPANTS.add(player.getUUID());
			NEEDS_NEEDS_SYNC.add(player.getUUID());
			if (sharedAvatar != null && player.isAlive() && !WAITING_FOR_RESPAWN.contains(player.getUUID())) {
				applySharedNeeds(player);
			}
			assignOpenAxis(player, server);
			sendAxisRole(player);
			if (WAITING_FOR_RESPAWN.contains(player.getUUID()) && player.isAlive()) {
				finishRestartRespawn(player);
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (WAITING_FOR_RESPAWN.contains(oldPlayer.getUUID())) finishRestartRespawn(newPlayer);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.player.getUUID();
			PARTICIPANTS.remove(playerId);
			CONTROLLER_DIMENSIONS.remove(playerId);
			NEEDS_NEEDS_SYNC.remove(playerId);
			boolean wasWaiting = WAITING_FOR_RESPAWN.remove(playerId);
			boolean hadRole = CONTROLLERS.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
			if (hadRole) {
				server.getPlayerList().broadcastSystemMessage(
					Component.literal(handler.player.getGameProfile().name() + " disconnected; their axis is open."), false);
			}
			if (wasWaiting) {
				server.getPlayerList().broadcastSystemMessage(
					Component.literal(handler.player.getGameProfile().name() + " left during the restart; the team can continue."), false);
			}
			updateRoleHud(server);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearState());
		ServerTickEvents.END_SERVER_TICK.register(ShareXYZ::controlPlayers);
		registerCommands();
		LOGGER.info("ShareXYZ is ready.");
	}

	public static boolean isChallengeParticipant(UUID playerId) {
		return PARTICIPANTS.contains(playerId);
	}

	public static void onPlayerAte(ServerPlayer player, int oldFood, float oldSaturation) {
		if (sharedAvatar == null || !PARTICIPANTS.contains(player.getUUID())) return;
		int newFood = player.getFoodData().getFoodLevel();
		float newSaturation = player.getFoodData().getSaturationLevel();
		if (newFood <= oldFood && newSaturation <= oldSaturation) return;
		sharedAvatar.foodLevel = newFood;
		sharedAvatar.saturation = newSaturation;
		for (ServerPlayer teammate : player.level().getServer().getPlayerList().getPlayers()) {
			if (teammate.isAlive()) {
				teammate.getFoodData().setFoodLevel(newFood);
				teammate.getFoodData().setSaturation(newSaturation);
			}
		}
	}

	private static void clearState() {
		CONTROLLERS.clear();
		CONTROLLER_DIMENSIONS.clear();
		NEEDS_NEEDS_SYNC.clear();
		PARTICIPANTS.clear();
		WAITING_FOR_RESPAWN.clear();
		LIVING_PLAYERS.clear();
		PLAYERS_BY_ID.clear();
		discardAvatarEntity();
		sharedAvatar = null;
		restarting = false;
		applyingGroupFallDamage = false;
		pendingGroupFallRestart = null;
		tickCounter = 0;
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			var status = Commands.literal("status").executes(context -> {
				MinecraftServer server = context.getSource().getServer();
				for (Axis axis : Axis.values()) {
					UUID id = CONTROLLERS.get(axis);
					ServerPlayer controller = id == null ? null : server.getPlayerList().getPlayer(id);
					String name = controller == null ? "open" : controller.getGameProfile().name();
					if (controller != null && !controller.isAlive()) name += " (dead; waiting)";
					if (controller != null && controller.isSpectator()) name += " (spectator; paused)";
					String displayName = name;
					context.getSource().sendSuccess(() -> Component.literal(axis.label + " axis: " + displayName), false);
				}
				String phase = WAITING_FOR_RESPAWN.isEmpty() ? "running" : "restart paused until dead teammates respawn or disconnect";
				context.getSource().sendSuccess(() -> Component.literal("Shared avatar: " + phase), false);
				return 1;
			});

			var assign = Commands.literal("assign")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(Commands.argument("axis", StringArgumentType.word())
					.then(Commands.argument("player", StringArgumentType.word()).executes(context -> {
						Axis axis = Axis.parse(StringArgumentType.getString(context, "axis"));
						if (axis == null) {
							context.getSource().sendFailure(Component.literal("Axis must be x, y, or z."));
							return 0;
						}
						ServerPlayer player = context.getSource().getServer().getPlayerList()
							.getPlayerByName(StringArgumentType.getString(context, "player"));
						if (player == null || !player.isAlive() || player.isSpectator()) {
							context.getSource().sendFailure(Component.literal("Choose an online, alive player who is not in spectator mode."));
							return 0;
						}
						assignAxis(axis, player, context.getSource().getServer());
						return 1;
					})));

			dispatcher.register(Commands.literal("sharedxyz").then(status).then(assign));
		});
	}

	private static void assignOpenAxis(ServerPlayer player, MinecraftServer server) {
		if (!player.isAlive() || player.isSpectator()) {
			player.sendSystemMessage(Component.literal("You are part of the shared avatar, but spectator mode cannot control an axis."));
			return;
		}
		for (Axis axis : Axis.values()) {
			if (player.getUUID().equals(CONTROLLERS.get(axis))) {
				player.sendSystemMessage(Component.literal("You still control the " + axis.label + " axis."));
				return;
			}
		}
		for (Axis axis : Axis.values()) {
			if (!CONTROLLERS.containsKey(axis)) {
				assignAxis(axis, player, server);
				return;
			}
		}
		player.sendSystemMessage(Component.literal("All axes have controllers. You still share the avatar; use /sharedxyz status to see roles."));
	}

	private static void assignAxis(Axis axis, ServerPlayer player, MinecraftServer server) {
		UUID id = player.getUUID();
		Axis previous = null;
		for (Map.Entry<Axis, UUID> entry : CONTROLLERS.entrySet()) {
			if (entry.getValue().equals(id)) previous = entry.getKey();
		}
		CONTROLLERS.entrySet().removeIf(entry -> entry.getValue().equals(id));
		CONTROLLERS.put(axis, id);
		String message = player.getGameProfile().name() + " now controls the " + axis.label + " axis (" + axisKeyHint(axis) + ")."
			+ (previous != null && previous != axis ? " Their old axis will be reassigned if another player is available." : "");
		server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
		CONTROLLER_DIMENSIONS.put(id, player.level().dimension());
		updateRoleHud(server);
	}

	private static void controlPlayers(MinecraftServer server) {
		tickCounter++;
		List<ServerPlayer> onlinePlayers = server.getPlayerList().getPlayers();
		if (onlinePlayers.isEmpty()) {
			clearGroup();
			return;
		}

		List<ServerPlayer> livingPlayers = LIVING_PLAYERS;
		Map<UUID, ServerPlayer> playersById = PLAYERS_BY_ID;
		livingPlayers.clear();
		playersById.clear();
		for (ServerPlayer player : onlinePlayers) {
			if (player.isAlive()) {
				livingPlayers.add(player);
				playersById.put(player.getUUID(), player);
			}
		}
		ensureControllersAvailable(livingPlayers, server);
		if (livingPlayers.isEmpty()) return;

		ServerPlayer dimensionChanger = WAITING_FOR_RESPAWN.isEmpty()
			? findControllerChangingDimension(playersById) : null;
		ResourceKey<Level> targetDimension = chooseSharedDimension(livingPlayers, dimensionChanger);
		ServerLevel targetLevel = server.getLevel(targetDimension);
		if (targetLevel == null) return;

		boolean paused = !WAITING_FOR_RESPAWN.isEmpty();

		// The carrier entity has to exist in the dimension the group currently shares.
		if (sharedAvatar == null) {
			ServerPlayer seed = dimensionChanger != null ? dimensionChanger : chooseControllerOrFirst(livingPlayers, targetDimension);
			sharedAvatar = createAvatar(targetLevel, seed.position());
		} else if (!sharedAvatar.dimension.equals(targetDimension)) {
			ServerPlayer seed = dimensionChanger != null ? dimensionChanger : chooseControllerOrFirst(livingPlayers, targetDimension);
			relocateAvatar(targetLevel, seed.position());
		}
		SharedAvatar avatar = sharedAvatar;
		SharedAvatarEntity carrier = avatar.entity;
		if (carrier == null) return;

		syncSharedNeeds(livingPlayers);

		// ------------------------------------------------------------------
		// 1. Turn the raw client input of the three controllers into intents.
		// ------------------------------------------------------------------
		ServerPlayer xController = currentController(Axis.X, playersById);
		ServerPlayer yController = currentController(Axis.Y, playersById);
		ServerPlayer zController = currentController(Axis.Z, playersById);
		double intentX = paused ? 0.0 : axisIntent(xController, Axis.X, targetDimension);
		double intentZ = paused ? 0.0 : axisIntent(zController, Axis.Z, targetDimension);
		double intentY = paused ? 0.0 : verticalIntent(yController, targetDimension);
		boolean sprinting = !paused && isSprinting(targetDimension, xController, yController, zController);

		// ------------------------------------------------------------------
		// 2. Integrate the movement and move the carrier.
		// ------------------------------------------------------------------
		Vec3 velocity = paused ? Vec3.ZERO : integrate(avatar, intentX, intentZ, intentY, sprinting);
		carrier.setDeltaMovement(velocity);
		carrier.move(MoverType.SELF, velocity);
		// Entity#move scales the delta movement (sneaking on ledges), restore ours.
		carrier.setDeltaMovement(velocity);
		if (carrier.onGround() && velocity.y < 0.0) {
			// The carrier rests on the ground. The gravity step of this tick has been
			// resolved by the collision, so the vertical velocity is dropped again.
			// Without this the velocity would accumulate while the avatar stands still
			// and the ground contact would flicker between "grounded" and "falling",
			// which makes the fall detection unreliable.
			velocity = new Vec3(velocity.x, 0.0, velocity.z);
			carrier.setDeltaMovement(velocity);
		}
		Vec3 position = carrier.position();
		avatar.position = position;
		avatar.velocity = velocity;
		avatar.onGround = carrier.onGround();

		// ------------------------------------------------------------------
		// 3. Fall damage hits the whole group, not the individual participants.
		// ------------------------------------------------------------------
		trackFall(avatar, position, livingPlayers);
		// Fall damage can kill a player, which restarts the group and replaces the
		// shared avatar synchronously. Do not continue this tick using the old carrier.
		if (sharedAvatar != avatar || avatar.entity != carrier || carrier.isRemoved()
				|| livingPlayers.stream().anyMatch(player -> !player.isAlive())) {
			return;
		}

		// ------------------------------------------------------------------
		// 4. Everybody rides the carrier, so their position is derived from it.
		// ------------------------------------------------------------------
		for (ServerPlayer player : livingPlayers) {
			if (player.isSpectator()) {
				// A spectator is not part of the body and keeps its own position.
				if (player.getVehicle() == carrier) player.stopRiding();
				continue;
			}
			if (!player.level().dimension().equals(targetDimension)) {
				// Leaving the vehicle first keeps the dimension change free of passengers.
				player.stopRiding();
				player.teleportTo(targetLevel, position.x, position.y, position.z,
					Set.of(), player.getYRot(), player.getXRot(), true);
			}
			if (player.getVehicle() != carrier) {
				player.stopRiding();
				if (player.startRiding(carrier, true, true)) {
					LOGGER.info("{} is now riding the shared avatar.", player.getGameProfile().name());
				} else if (tickCounter % 20 == 0) {
					LOGGER.warn("{} could not be mounted onto the shared avatar; the group would desync.",
						player.getGameProfile().name());
				}
			}
			carrier.positionRider(player);
			// The participants never fall on their own; the carrier handles that.
			player.fallDistance = 0.0;
		}

		// ------------------------------------------------------------------
		// 5. Bookkeeping.
		// ------------------------------------------------------------------
		CONTROLLER_DIMENSIONS.keySet().retainAll(CONTROLLERS.values());
		for (ServerPlayer player : livingPlayers) {
			if (CONTROLLERS.containsValue(player.getUUID())) {
				CONTROLLER_DIMENSIONS.put(player.getUUID(), player.level().dimension());
			}
			if (tickCounter % HUD_UPDATE_INTERVAL == 0) sendRoleHud(player, server, paused);
		}
	}

	/**
	 * Resets everything that belongs to the group without touching the role assignments of
	 * the (now offline) players.
	 */
	private static void clearGroup() {
		CONTROLLER_DIMENSIONS.clear();
		WAITING_FOR_RESPAWN.clear();
		LIVING_PLAYERS.clear();
		PLAYERS_BY_ID.clear();
		discardAvatarEntity();
		sharedAvatar = null;
	}

	private static SharedAvatar createAvatar(ServerLevel level, Vec3 position) {
		SharedAvatarEntity carrier = new SharedAvatarEntity(SharedAvatarEntity.TYPE, level);
		carrier.setPos(position.x, position.y, position.z);
		carrier.setOldPosAndRot();
		carrier.setDeltaMovement(Vec3.ZERO);
		level.addFreshEntity(carrier);

		SharedAvatar avatar = new SharedAvatar(level.dimension());
		avatar.entity = carrier;
		avatar.position = position;
		avatar.fallStartY = position.y;
		return avatar;
	}

	private static void relocateAvatar(ServerLevel level, Vec3 position) {
		if (sharedAvatar == null) return;
		discardAvatarEntity();
		SharedAvatarEntity carrier = new SharedAvatarEntity(SharedAvatarEntity.TYPE, level);
		carrier.setPos(position.x, position.y, position.z);
		carrier.setOldPosAndRot();
		carrier.setDeltaMovement(Vec3.ZERO);
		level.addFreshEntity(carrier);
		sharedAvatar.entity = carrier;
		sharedAvatar.dimension = level.dimension();
		sharedAvatar.position = position;
		sharedAvatar.velocity = Vec3.ZERO;
		sharedAvatar.fallStartY = position.y;
		sharedAvatar.wasOnGround = true;
	}

	private static void discardAvatarEntity() {
		if (sharedAvatar == null || sharedAvatar.entity == null) return;
		sharedAvatar.entity.ejectPassengers();
		sharedAvatar.entity.discard();
		sharedAvatar.entity = null;
	}

	private static ResourceKey<Level> chooseSharedDimension(List<ServerPlayer> livingPlayers, ServerPlayer dimensionChanger) {
		if (dimensionChanger != null) return dimensionChanger.level().dimension();
		if (sharedAvatar != null) {
			for (ServerPlayer player : livingPlayers) {
				if (player.level().dimension().equals(sharedAvatar.dimension)) return sharedAvatar.dimension;
			}
		}
		UUID xId = CONTROLLERS.get(Axis.X);
		for (ServerPlayer player : livingPlayers) {
			if (!player.isSpectator() && player.getUUID().equals(xId)) return player.level().dimension();
		}
		return livingPlayers.getFirst().level().dimension();
	}

	private static ServerPlayer chooseControllerOrFirst(List<ServerPlayer> players, ResourceKey<Level> dimension) {
		UUID xId = CONTROLLERS.get(Axis.X);
		ServerPlayer firstInDimension = null;
		for (ServerPlayer player : players) {
			if (!player.level().dimension().equals(dimension)) continue;
			if (player.getUUID().equals(xId)) return player;
			if (firstInDimension == null || player.getUUID().compareTo(firstInDimension.getUUID()) < 0) firstInDimension = player;
		}
		return firstInDimension != null ? firstInDimension : players.getFirst();
	}

	private static ServerPlayer findControllerChangingDimension(Map<UUID, ServerPlayer> playersById) {
		for (Axis axis : Axis.values()) {
			ServerPlayer controller = currentController(axis, playersById);
			if (controller == null) continue;
			ResourceKey<Level> previous = CONTROLLER_DIMENSIONS.get(controller.getUUID());
			if (previous != null && !previous.equals(controller.level().dimension())) return controller;
		}
		return null;
	}

	/**
	 * Reads the raw, unfiltered key state of a controller and turns it into a world-axis
	 * aligned intent.
	 *
	 * <p>The mapping is deliberately world-axis aligned instead of camera relative: a
	 * camera relative projection degenerates for every yaw that is parallel to the
	 * controlled axis, which silently killed half of the keys. Here A/D always drive X and
	 * W/S always drive Z, at full speed, in every camera direction.
	 */
	private static double axisIntent(ServerPlayer controller, Axis axis, ResourceKey<Level> dimension) {
		Input input = controllerInput(controller, dimension);
		if (input == null) return 0.0;
		return switch (axis) {
			// A/D drive the world X axis: D = east (+X), A = west (-X).
			case X -> (input.right() ? 1.0 : 0.0) - (input.left() ? 1.0 : 0.0);
			// W/S drive the world Z axis: W = south (+Z), S = north (-Z).
			case Z -> (input.forward() ? 1.0 : 0.0) - (input.backward() ? 1.0 : 0.0);
			case Y -> 0.0;
		};
	}

	/** Space lifts the shared avatar, sneak pushes it down, releasing it lets it fall. */
	private static double verticalIntent(ServerPlayer controller, ResourceKey<Level> dimension) {
		Input input = controllerInput(controller, dimension);
		if (input == null) return 0.0;
		return (input.jump() ? 1.0 : 0.0) - (input.shift() ? 1.0 : 0.0);
	}

	private static boolean isSprinting(ResourceKey<Level> dimension, ServerPlayer... controllers) {
		for (ServerPlayer controller : controllers) {
			Input input = controllerInput(controller, dimension);
			if (input != null && input.sprint()) return true;
		}
		return false;
	}

	private static Input controllerInput(ServerPlayer controller, ResourceKey<Level> dimension) {
		if (controller == null) return null;
		if (dimension != null && !controller.level().dimension().equals(dimension)) return null;
		return controller.getLastClientInput();
	}

	/**
	 * Integrates one tick of movement for the shared avatar.
	 */
	private static Vec3 integrate(SharedAvatar avatar, double intentX, double intentZ, double intentY, boolean sprinting) {
		double speed = WALK_SPEED * (sprinting ? SPRINT_MULTIPLIER : 1.0);
		double vx = approach(avatar.velocity.x, intentX * speed);
		double vz = approach(avatar.velocity.z, intentZ * speed);

		double vy;
		if (intentY > 0.0) {
			vy = RISE_SPEED;
		} else if (intentY < 0.0) {
			vy = -DESCEND_SPEED;
		} else {
			// Nobody owns the Y axis right now: the shared avatar keeps falling.
			vy = Math.max(-TERMINAL_VELOCITY, avatar.velocity.y - VERTICAL_GRAVITY);
		}
		return new Vec3(vx, vy, vz);
	}

	private static double approach(double current, double target) {
		if (target == 0.0) {
			double next = current * HORIZONTAL_FRICTION;
			return Math.abs(next) < 0.0025 ? 0.0 : next;
		}
		return current + (target - current) * HORIZONTAL_ACCELERATION;
	}

	/**
	 * Tracks the fall of the shared avatar and applies the damage to the whole group.
	 */
	private static void trackFall(SharedAvatar avatar, Vec3 position, List<ServerPlayer> players) {
		if (avatar.onGround) {
			if (!avatar.wasOnGround) {
				double drop = avatar.fallStartY - position.y;
				if (drop > FALL_DAMAGE_THRESHOLD) {
					float damage = (float) Math.floor(drop - FALL_DAMAGE_THRESHOLD);
					LOGGER.info("The shared avatar fell {} blocks, the group takes {} damage.",
						String.format(java.util.Locale.ROOT, "%.1f", drop), damage);
					applyGroupFallDamage(players, damage);
				}
			}
			avatar.fallStartY = position.y;
			avatar.wasOnGround = true;
		} else {
			avatar.wasOnGround = false;
		}
	}

	private static void applyGroupFallDamage(List<ServerPlayer> players, float damage) {
		if (damage <= 0.0F) return;
		DamageSource fall = null;
		applyingGroupFallDamage = true;
		try {
			for (ServerPlayer player : players) {
				if (!player.isAlive()) continue;
				if (fall == null) {
					Registry<DamageType> damageTypes = player.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
					fall = new DamageSource(damageTypes.getOrThrow(DamageTypes.FALL));
				}
				player.hurtServer(player.level(), fall, damage);
			}
		} finally {
			applyingGroupFallDamage = false;
		}
		ServerPlayer deadPlayer = pendingGroupFallRestart;
		pendingGroupFallRestart = null;
		if (deadPlayer != null) {
			restartAfterDeath(deadPlayer);
		}
	}

	private static ServerPlayer currentController(Axis axis, Map<UUID, ServerPlayer> playersById) {
		UUID id = CONTROLLERS.get(axis);
		ServerPlayer player = id == null ? null : playersById.get(id);
		return player != null && player.isAlive() && !player.isSpectator() ? player : null;
	}

	private static void ensureControllersAvailable(List<ServerPlayer> livingPlayers, MinecraftServer server) {
		for (Axis axis : Axis.values()) {
			if (CONTROLLERS.containsKey(axis)) continue; // Dead controllers keep their role until respawn/disconnect.
			for (ServerPlayer candidate : livingPlayers) {
				if (candidate.isSpectator() || CONTROLLERS.containsValue(candidate.getUUID())) continue;
				CONTROLLERS.put(axis, candidate.getUUID());
				CONTROLLER_DIMENSIONS.put(candidate.getUUID(), candidate.level().dimension());
				server.getPlayerList().broadcastSystemMessage(Component.literal(
					candidate.getGameProfile().name() + " now controls the " + axis.label + " axis (" + axisKeyHint(axis) + ")."), false);
				updateRoleHud(server);
				break;
			}
		}
	}

	private static void sendRoleHud(ServerPlayer player, MinecraftServer server, boolean paused) {
		StringBuilder text = new StringBuilder(paused ? "RESTART WAIT  " : "XYZ  ");
		for (Axis axis : Axis.values()) {
			if (axis != Axis.X) text.append("  ");
			UUID id = CONTROLLERS.get(axis);
			ServerPlayer controller = id == null ? null : server.getPlayerList().getPlayer(id);
			String name = controller == null ? "open" : controller.getGameProfile().name();
			if (controller != null && !controller.isAlive()) name = "dead";
			if (controller != null && controller.isSpectator()) name = "paused";
			if (name.length() > 9) name = name.substring(0, 8) + "…";
			text.append(axis.label).append(':').append(name);
			if (id != null && id.equals(player.getUUID())) text.append('*');
		}
		player.sendSystemMessage(Component.literal(text.toString()), true);
	}

	private static void updateRoleHud(MinecraftServer server) {
		boolean paused = !WAITING_FOR_RESPAWN.isEmpty();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendAxisRole(player);
			if (player.isAlive()) sendRoleHud(player, server, paused);
		}
	}

	private static String axisKeyHint(Axis axis) {
		return switch (axis) {
			case X -> "D/A move X (east/west)";
			case Y -> "Space/Shift move Y (up/down)";
			case Z -> "W/S move Z (south/north)";
		};
	}

	private static void sendAxisRole(ServerPlayer player) {
		byte role = -1;
		UUID playerId = player.getUUID();
		for (Axis axis : Axis.values()) {
			if (playerId.equals(CONTROLLERS.get(axis))) {
				role = (byte) axis.ordinal();
				break;
			}
		}
		ServerPlayNetworking.send(player, new AxisRolePayload(role));
	}

	private static void restartAfterDeath(ServerPlayer deadPlayer) {
		if (deadPlayer.level().isClientSide()) return;
		if (restarting || !WAITING_FOR_RESPAWN.isEmpty()) {
			if (WAITING_FOR_RESPAWN.add(deadPlayer.getUUID())) {
				deadPlayer.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal(
					deadPlayer.getGameProfile().name() + " also died during the restart; the group remains paused."), false);
			}
			return;
		}
		restarting = true;
		MinecraftServer server = deadPlayer.level().getServer();
		if (server == null) { restarting = false; return; }

		ServerLevel startLevel = server.overworld();
		ResourceKey<Level> dimension = startLevel.dimension();
		Vec3 start = Vec3.atBottomCenterOf(startLevel.getRespawnData().pos());
		discardAvatarEntity();
		sharedAvatar = createAvatar(startLevel, start);
		WAITING_FOR_RESPAWN.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CONTROLLER_DIMENSIONS.remove(player.getUUID());
			NEEDS_NEEDS_SYNC.remove(player.getUUID());
			if (!player.isAlive()) {
				WAITING_FOR_RESPAWN.add(player.getUUID());
				continue;
			}
			player.teleportTo(startLevel, start.x, start.y, start.z, Set.of(), player.getYRot(), player.getXRot(), true);
			resetRunProgress(player);
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal(
			deadPlayer.getGameProfile().name() + " died. The team restarted at world spawn." +
			(WAITING_FOR_RESPAWN.isEmpty() ? "" : " Waiting for fallen teammates to respawn or disconnect.")), false);
		server.execute(() -> restarting = false);
	}

	private static void finishRestartRespawn(ServerPlayer player) {
		if (!WAITING_FOR_RESPAWN.remove(player.getUUID())) return;
		resetRunProgress(player);
		if (sharedAvatar != null) {
			ServerLevel level = player.level().getServer().getLevel(sharedAvatar.dimension);
			if (level != null) {
				Vec3 pos = sharedAvatar.position;
				player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), true);
			}
		}
		CONTROLLER_DIMENSIONS.remove(player.getUUID());
		sendAxisRole(player);
		player.sendSystemMessage(Component.literal(WAITING_FOR_RESPAWN.isEmpty()
			? "The team is ready. The shared run continues from spawn."
			: "You respawned. Waiting for the other fallen teammates."));
	}

	private static void resetRunProgress(ServerPlayer player) {
		player.closeContainer();
		player.getInventory().clearContent();
		player.setExperienceLevels(0);
		player.setExperiencePoints(0);
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5.0F);
		player.setAbsorptionAmount(0.0F);
		player.setRemainingFireTicks(0);
		player.setAirSupply(player.getMaxAirSupply());
		player.fallDistance = 0.0;
		player.setDeltaMovement(Vec3.ZERO);
		player.removeAllEffects();
	}

	private static void syncSharedNeeds(List<ServerPlayer> players) {
		float lowestHealth = Float.MAX_VALUE;
		int lowestFood = 20;
		float lowestSaturation = Float.MAX_VALUE;
		int lowestAir = Integer.MAX_VALUE;
		for (ServerPlayer player : players) {
			lowestHealth = Math.min(lowestHealth, player.getHealth());
			lowestFood = Math.min(lowestFood, player.getFoodData().getFoodLevel());
			lowestSaturation = Math.min(lowestSaturation, player.getFoodData().getSaturationLevel());
			lowestAir = Math.min(lowestAir, player.getAirSupply());
		}
		sharedAvatar.health = lowestHealth;
		sharedAvatar.foodLevel = lowestFood;
		sharedAvatar.saturation = lowestSaturation;
		sharedAvatar.airSupply = lowestAir;
		for (ServerPlayer player : players) {
			applySharedNeeds(player);
			// The one-off synchronisation marker has been dealt with.
			NEEDS_NEEDS_SYNC.remove(player.getUUID());
		}
	}

	private static void applySharedNeeds(ServerPlayer player) {
		float health = Math.min(player.getMaxHealth(), sharedAvatar.health);
		if (Math.abs(player.getHealth() - health) > 0.001F) player.setHealth(health);
		if (player.getFoodData().getFoodLevel() != sharedAvatar.foodLevel) {
			player.getFoodData().setFoodLevel(sharedAvatar.foodLevel);
		}
		if (Math.abs(player.getFoodData().getSaturationLevel() - sharedAvatar.saturation) > 0.001F) {
			player.getFoodData().setSaturation(sharedAvatar.saturation);
		}
		if (player.getAirSupply() != sharedAvatar.airSupply) player.setAirSupply(sharedAvatar.airSupply);
	}

	private static final class SharedAvatar {
		private ResourceKey<Level> dimension;
		private Vec3 position;
		private Vec3 velocity = Vec3.ZERO;
		private boolean onGround = true;
		private boolean wasOnGround = true;
		private double fallStartY;
		private SharedAvatarEntity entity;
		private float health = 20.0F;
		private int foodLevel = 20;
		private float saturation = 5.0F;
		private int airSupply = 300;
		private SharedAvatar(ResourceKey<Level> dimension) {
			this.dimension = dimension;
		}
	}

	private enum Axis {
		X("X"), Y("Y"), Z("Z");
		private final String label;
		Axis(String label) { this.label = label; }
		private static Axis parse(String value) {
			return switch (value.toLowerCase()) {
				case "x" -> X;
				case "y" -> Y;
				case "z" -> Z;
				default -> null;
			};
		}
	}

	public static void onPlayerDeath(ServerPlayer player) {
		if (applyingGroupFallDamage) {
			if (pendingGroupFallRestart == null) pendingGroupFallRestart = player;
			return;
		}
		restartAfterDeath(player);
	}
}
