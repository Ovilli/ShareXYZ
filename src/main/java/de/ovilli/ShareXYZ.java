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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
	 * and turns it into a per-axis intent. Because horizontal intents are world-axis aligned
	 * (A/D drive X and W/S drive Z) they never degenerate: there is no camera
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
	/** Initial vertical speed for a normal player jump. */
	private static final double JUMP_SPEED = 0.42;
	private static final long HUD_UPDATE_INTERVAL = 20;

	private static final Map<Axis, UUID> CONTROLLERS = new EnumMap<>(Axis.class);
	/** Dimension each controller was last seen in. Used to detect a controller that changed dimension. */
	private static final Map<UUID, ResourceKey<Level>> CONTROLLER_DIMENSIONS = new HashMap<>();
	/** Last server tick before which each player must not be mounted onto a new carrier. */
	private static final Map<UUID, Long> MOUNT_DEFERRED_THROUGH = new HashMap<>();
	private static final Set<UUID> PARTICIPANTS = new HashSet<>();
	private static final Set<UUID> WAITING_FOR_RESPAWN = new HashSet<>();
	private static final List<ServerPlayer> LIVING_PLAYERS = new ArrayList<>(3);

	/**
	 * The chunk ticket that keeps the carrier's chunk loaded.
	 *
	 * <p>A chunk is only loaded while a player is near it. The carrier is the one body
	 * everybody shares, and it has to survive moments in which no participant is standing
	 * next to it - for example right after a group death, when everybody is on the death
	 * screen and the chunk of the world spawn would be unloaded. An entity in an unloaded
	 * chunk is removed by the server, so the carrier would disappear.
	 */
	private static final TicketType CARRIER_TICKET = new TicketType(TicketType.NO_TIMEOUT,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
	/** The level and chunk the carrier ticket currently holds. */
	private static ServerLevel carrierTicketLevel;
	private static ChunkPos carrierTicketPos;

	private static SharedAvatar sharedAvatar;
	private static boolean restarting;
	private static boolean applyingGroupFallDamage;
	/** True while the group is taken down because one of its members died. */
	private static boolean applyingGroupDeath;
	private static ServerPlayer pendingGroupFallRestart;
	private static long tickCounter;

	@Override
	public void onInitialize() {
		SharedAvatarEntity.register();
		PayloadTypeRegistry.clientboundPlay().register(AxisRolePayload.TYPE, AxisRolePayload.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			PARTICIPANTS.add(player.getUUID());
			// A joining client may not have received the existing carrier's spawn packet
			// yet. Give entity tracking one full tick before sending its passenger update.
			MOUNT_DEFERRED_THROUGH.put(player.getUUID(), tickCounter + 1);
			if (sharedAvatar != null && player.isAlive() && !player.isSpectator() && !player.isCreative()
					&& !WAITING_FOR_RESPAWN.contains(player.getUUID())) {
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
			MOUNT_DEFERRED_THROUGH.remove(playerId);
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
		if (sharedAvatar == null || player.isSpectator() || player.isCreative()
				|| !PARTICIPANTS.contains(player.getUUID())) return;
		int newFood = player.getFoodData().getFoodLevel();
		float newSaturation = player.getFoodData().getSaturationLevel();
		if (newFood <= oldFood && newSaturation <= oldSaturation) return;
		sharedAvatar.foodLevel = newFood;
		sharedAvatar.saturation = newSaturation;
		for (ServerPlayer teammate : player.level().getServer().getPlayerList().getPlayers()) {
			if (!teammate.isAlive() || teammate.isSpectator() || teammate.isCreative()
					|| !PARTICIPANTS.contains(teammate.getUUID())) continue;
			teammate.getFoodData().setFoodLevel(newFood);
			teammate.getFoodData().setSaturation(newSaturation);
		}
	}

	private static void clearState() {
		CONTROLLERS.clear();
		CONTROLLER_DIMENSIONS.clear();
		MOUNT_DEFERRED_THROUGH.clear();
		PARTICIPANTS.clear();
		WAITING_FOR_RESPAWN.clear();
		LIVING_PLAYERS.clear();
		discardAvatarEntity();
		sharedAvatar = null;
		restarting = false;
		applyingGroupFallDamage = false;
		applyingGroupDeath = false;
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
				String phase = WAITING_FOR_RESPAWN.isEmpty()
					? "running" : "the shared run is paused while fallen participants respawn or disconnect";
				context.getSource().sendSuccess(() -> Component.literal("Shared avatar: " + phase), false);
				return 1;
			});

			var assign = Commands.literal("assign")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(Commands.argument("axis", StringArgumentType.word())
					.suggests((context, builder) -> builder.suggest("x").suggest("y").suggest("z").buildFuture())
					.then(Commands.argument("player", EntityArgument.player()).executes(context -> {
						Axis axis = Axis.parse(StringArgumentType.getString(context, "axis"));
						if (axis == null) {
							context.getSource().sendFailure(Component.literal("Axis must be x, y, or z."));
							return 0;
						}
						ServerPlayer player = EntityArgument.getPlayer(context, "player");
						if (!player.isAlive() || player.isSpectator()) {
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
		livingPlayers.clear();
		for (ServerPlayer player : onlinePlayers) {
			if (player.isAlive() && !player.isSpectator()) livingPlayers.add(player);
		}
		ensureControllersAvailable(livingPlayers, server);
		// The chunk of the carrier has to stay loaded, also while the group waits for its
		// fallen members: in that phase nobody holds the chunk, and an entity in an
		// unloaded chunk is removed by the server. The ticket follows the carrier, so it
		// is refreshed on every tick - also on the paths that return early below.
		if (sharedAvatar != null && sharedAvatar.entity != null && !sharedAvatar.entity.isRemoved()
				&& sharedAvatar.entity.level() instanceof ServerLevel carrierLevel) {
			holdCarrierChunk(carrierLevel, sharedAvatar.entity.position());
		}
		if (livingPlayers.isEmpty()) {
			// Spectators do not belong to the shared body's position or movement. Keep a
			// dormant carrier in place if everyone is spectating, so it can be reused if
			// a participant returns to an active game mode later.
			if (sharedAvatar != null) {
				sharedAvatar.velocity = Vec3.ZERO;
				if (sharedAvatar.entity != null) {
					sharedAvatar.entity.setDeltaMovement(Vec3.ZERO);
					for (ServerPlayer player : onlinePlayers) {
						if (player.getVehicle() == sharedAvatar.entity) player.stopRiding();
					}
				}
			}
			return;
		}

		ServerPlayer dimensionChanger = WAITING_FOR_RESPAWN.isEmpty()
			? findControllerChangingDimension(server) : null;
		ResourceKey<Level> targetDimension = chooseSharedDimension(livingPlayers, dimensionChanger);
		ServerLevel targetLevel = server.getLevel(targetDimension);
		if (targetLevel == null) return;

		boolean paused = !WAITING_FOR_RESPAWN.isEmpty();

		// The carrier entity has to exist in the dimension the group currently shares.
		if (sharedAvatar == null) {
			ServerPlayer seed = dimensionChanger != null ? dimensionChanger : chooseControllerOrFirst(livingPlayers, targetDimension);
			sharedAvatar = createAvatar(targetLevel, seed.position(), tickCounter);
		} else if (!sharedAvatar.dimension.equals(targetDimension)) {
			ServerPlayer seed = dimensionChanger != null ? dimensionChanger : chooseControllerOrFirst(livingPlayers, targetDimension);
			relocateAvatar(targetLevel, seed.position());
		} else if (sharedAvatar.entity == null || sharedAvatar.entity.isRemoved()
				|| sharedAvatar.entity.level() != targetLevel) {
			// The carrier was lost (it fell into the void, a command removed it, ...).
			// Without a new body the group would stand still forever, so build a new one.
			// While the group waits for its fallen members the carrier belongs at the
			// world spawn, because that is where the challenge restarts.
			ServerPlayer seed = dimensionChanger != null ? dimensionChanger : chooseControllerOrFirst(livingPlayers, targetDimension);
			Vec3 respawn = paused ? safeSpawnPosition(targetLevel) : seed.position();
			LOGGER.warn("The shared avatar was lost and has been recreated ({}).",
				describeLostCarrier(sharedAvatar.entity, targetLevel));
			sharedAvatar = createAvatar(targetLevel, respawn, tickCounter);
		}
		SharedAvatar avatar = sharedAvatar;
		SharedAvatarEntity carrier = avatar.entity;
		if (carrier == null) return;

		syncSharedNeeds(livingPlayers);

		// ------------------------------------------------------------------
		// 1. Turn the raw client input of the three controllers into intents.
		// ------------------------------------------------------------------
		ServerPlayer xController = currentController(Axis.X, server);
		ServerPlayer yController = currentController(Axis.Y, server);
		ServerPlayer zController = currentController(Axis.Z, server);
		double intentX = paused ? 0.0 : axisIntent(xController, Axis.X, targetDimension);
		double intentZ = paused ? 0.0 : axisIntent(zController, Axis.Z, targetDimension);
		boolean jumping = !paused && jumpIntent(yController, targetDimension);
		boolean sprinting = !paused && isSprinting(targetDimension, xController, yController, zController);

		// ------------------------------------------------------------------
		// 2. Integrate the movement and move the carrier.
		// ------------------------------------------------------------------
		// While the group waits for its fallen members the participants do not steer the
		// avatar, but the avatar still has to fall: a carrier that hangs in the air would
		// drop the whole group as soon as the challenge continues.
		Vec3 velocity = integrate(avatar, intentX, intentZ, jumping, sprinting);
		Vec3 previousPosition = carrier.position();
		carrier.setDeltaMovement(velocity);
		carrier.move(MoverType.SELF, velocity);
		Vec3 actualMovement = carrier.position().subtract(previousPosition);
		if (velocity.x != 0.0 && Math.abs(actualMovement.x) < Math.abs(velocity.x) * 0.5) {
			velocity = new Vec3(0.0, velocity.y, velocity.z);
		}
		if (velocity.z != 0.0 && Math.abs(actualMovement.z) < Math.abs(velocity.z) * 0.5) {
			velocity = new Vec3(velocity.x, velocity.y, 0.0);
		}
		// Keep collision-resolved velocity instead of repeatedly pushing into a wall.
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
		// The carrier has to stay in a loaded chunk, even when no participant stands
		// next to it. Otherwise the server would unload it together with the chunk.
		holdCarrierChunk(targetLevel, position);

		// ------------------------------------------------------------------
		// 3. Fall damage hits the whole group, not the individual participants.
		// ------------------------------------------------------------------
		// A plain entity never runs vanilla's fall check on its own. Doing it here keeps
		// all vanilla landing rules (water, hay bales, slime blocks, beds, honey, ...):
		// the landing block hands its damage multiplier to
		// SharedAvatarEntity#causeFallDamage, which forwards the result to the group.
		// Entering water has to cancel the fall that happened before it; the carrier is
		// moved after its own tick, so reset it explicitly here.
		if (carrier.isInWater()) carrier.resetFallDistance();
		carrier.doCheckFallDamage(actualMovement.x, actualMovement.y, actualMovement.z, carrier.onGround());
		// Fall damage can kill a player, which restarts the group and replaces the
		// shared avatar synchronously. Do not continue this tick using the old carrier.
		if (sharedAvatar != avatar || avatar.entity != carrier || carrier.isRemoved()
				|| livingPlayers.stream().anyMatch(player -> !player.isAlive())) {
			return;
		}

		// ------------------------------------------------------------------
		// 4. Everybody rides the carrier, so their position is derived from it.
		// ------------------------------------------------------------------
		// Move players into the target dimension before mounting them. Entity tracking
		// starts there on the next tick, so teleporting and mounting in one tick can
		// send a passenger update before the carrier spawn packet.
		for (ServerPlayer player : onlinePlayers) {
			if (player.isSpectator() && player.getVehicle() == carrier) {
				player.stopRiding();
				MOUNT_DEFERRED_THROUGH.remove(player.getUUID());
			}
		}
		for (ServerPlayer player : livingPlayers) {
			if (!player.level().dimension().equals(targetDimension)) {
				player.stopRiding();
				player.teleportTo(targetLevel, position.x, position.y, position.z,
					Set.of(), player.getYRot(), player.getXRot(), true);
				MOUNT_DEFERRED_THROUGH.put(player.getUUID(), tickCounter);
			}
		}
		// Let the carrier's spawn packet reach clients before sending passenger
		// updates. This also covers a player who joined an already-running group.
		if (avatar.createdAtTick == tickCounter) return;
		for (ServerPlayer player : livingPlayers) {
			if (tickCounter <= MOUNT_DEFERRED_THROUGH.getOrDefault(player.getUUID(), Long.MIN_VALUE)) continue;
			if (player.getVehicle() != carrier) {
				player.stopRiding();
				if (!player.startRiding(carrier, true, true)) {
					if (tickCounter % 20 == 0) {
						LOGGER.warn("{} could not be mounted onto the shared avatar; the group would desync.",
							player.getGameProfile().name());
					}
					continue;
				}
				LOGGER.info("{} is now riding the shared avatar.", player.getGameProfile().name());
			}
			MOUNT_DEFERRED_THROUGH.remove(player.getUUID());
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
			if (tickCounter % HUD_UPDATE_INTERVAL == 0) sendAxisRole(player);
		}
	}

	/**
	 * Resets everything that belongs to the group without touching the role assignments of
	 * the (now offline) players.
	 */
	private static void clearGroup() {
		CONTROLLER_DIMENSIONS.clear();
		MOUNT_DEFERRED_THROUGH.clear();
		WAITING_FOR_RESPAWN.clear();
		LIVING_PLAYERS.clear();
		discardAvatarEntity();
		sharedAvatar = null;
	}

	private static SharedAvatar createAvatar(ServerLevel level, Vec3 position, long createdAtTick) {
		SharedAvatarEntity carrier = new SharedAvatarEntity(SharedAvatarEntity.TYPE, level);
		carrier.setPos(position.x, position.y, position.z);
		carrier.setOldPosAndRot();
		carrier.setDeltaMovement(Vec3.ZERO);
		level.addFreshEntity(carrier);

		SharedAvatar avatar = new SharedAvatar(level.dimension());
		avatar.entity = carrier;
		avatar.position = position;
		avatar.createdAtTick = createdAtTick;
		holdCarrierChunk(level, position);
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
		sharedAvatar.createdAtTick = tickCounter;
		holdCarrierChunk(level, position);
	}

	private static void discardAvatarEntity() {
		releaseCarrierChunk();
		if (sharedAvatar == null || sharedAvatar.entity == null) return;
		sharedAvatar.entity.ejectPassengers();
		sharedAvatar.entity.discard();
		sharedAvatar.entity = null;
	}

	/**
	 * Keeps the chunk of the carrier loaded, so the server does not unload the carrier
	 * together with its chunk. The ticket follows the carrier when it moves into another
	 * chunk and is dropped when the carrier is discarded.
	 */
	private static void holdCarrierChunk(ServerLevel level, Vec3 position) {
		ChunkPos chunk = ChunkPos.containing(BlockPos.containing(position));
		if (carrierTicketLevel == level && chunk.equals(carrierTicketPos)) return;
		releaseCarrierChunk();
		level.getChunkSource().addTicketWithRadius(CARRIER_TICKET, chunk, 0);
		carrierTicketLevel = level;
		carrierTicketPos = chunk;
		LOGGER.debug("Carrier chunk ticket for chunk {} {} (chunk loaded: {}).", chunk.x(), chunk.z(),
			level.getChunkSource().hasChunk(chunk.x(), chunk.z()));
	}

	private static void releaseCarrierChunk() {
		if (carrierTicketLevel == null || carrierTicketPos == null) return;
		LOGGER.debug("Carrier chunk ticket for chunk {} {} released.", carrierTicketPos.x(), carrierTicketPos.z());
		carrierTicketLevel.getChunkSource().removeTicketWithRadius(CARRIER_TICKET, carrierTicketPos, 0);
		carrierTicketLevel = null;
		carrierTicketPos = null;
	}

	/**
	 * The position the challenge restarts at: the world spawn, but on solid ground.
	 *
	 * <p>The stored world spawn keeps the height it had when the world was created, so it
	 * can be a few blocks above the terrain. A carrier that is dropped there would fall -
	 * and take the whole group down with it, although nobody did anything wrong.
	 */
	private static Vec3 safeSpawnPosition(ServerLevel level) {
		BlockPos spawn = level.getRespawnData().pos();
		int x = spawn.getX();
		int z = spawn.getZ();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new Vec3(x + 0.5, y, z + 0.5);
	}

	/**
	 * Describes why the carrier entity was considered lost. The reason belongs into the log,
	 * because a carrier that disappears right after a restart is a bug that has to be visible.
	 */
	private static String describeLostCarrier(SharedAvatarEntity entity, ServerLevel targetLevel) {
		if (entity == null) return "the carrier entity was never created";
		ChunkPos chunk = ChunkPos.containing(BlockPos.containing(entity.position()));
		String state = "chunk " + chunk.x() + "/" + chunk.z()
			+ " loaded=" + targetLevel.getChunkSource().hasChunk(chunk.x(), chunk.z())
			+ " ticketed=" + (carrierTicketPos != null && carrierTicketPos.equals(chunk));
		if (entity.isRemoved()) {
			return "the carrier entity was removed (" + entity.getRemovalReason() + ", " + state + ")";
		}
		return "the carrier entity is in " + entity.level().dimension().identifier()
			+ " instead of " + targetLevel.dimension().identifier() + " (" + state + ")";
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

	private static ServerPlayer findControllerChangingDimension(MinecraftServer server) {
		for (Axis axis : Axis.values()) {
			ServerPlayer controller = currentController(axis, server);
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

	/** Space makes the Y controller jump from the ground; gravity handles vertical movement otherwise. */
	private static boolean jumpIntent(ServerPlayer controller, ResourceKey<Level> dimension) {
		Input input = controllerInput(controller, dimension);
		return input != null && input.jump();
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
	private static Vec3 integrate(SharedAvatar avatar, double intentX, double intentZ, boolean jumping, boolean sprinting) {
		double speed = WALK_SPEED * (sprinting ? SPRINT_MULTIPLIER : 1.0);
		double vx = approach(avatar.velocity.x, intentX * speed);
		double vz = approach(avatar.velocity.z, intentZ * speed);

		double vy;
		if (avatar.onGround && jumping) {
			// A normal jump gives the shared body an initial upward impulse. There is no
			// sustained lift or controlled descent; gravity takes over on the next tick.
			vy = JUMP_SPEED;
		} else {
			// Gravity always acts while airborne, just as it does for a normal player.
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
	 * Called by the carrier when vanilla's fall check lands it. Each active survival or
	 * adventure participant gets the same fall distance and landing block multiplier, then
	 * their player entity applies vanilla fall damage with their own protections.
	 */
	public static boolean onSharedAvatarFell(double fallDistance, float damageMultiplier, DamageSource source) {
		if (fallDistance <= 0.0 || LIVING_PLAYERS.isEmpty()) return false;
		if (!WAITING_FOR_RESPAWN.isEmpty()) {
			// The group is down and waits for its fallen members. The carrier still has to
			// settle on the ground after the reset, but that must not hurt anybody: the
			// fall was not caused by the players.
			LOGGER.info("The shared avatar landed while the group was down; no fall damage is applied.");
			return false;
		}
		DamageSource fall = source != null ? source : damageSource(LIVING_PLAYERS.getFirst(), DamageTypes.FALL);
		boolean applied = false;
		applyingGroupFallDamage = true;
		try {
			for (ServerPlayer player : LIVING_PLAYERS) {
				if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
				applied |= player.causeFallDamage(fallDistance, damageMultiplier, fall);
			}
		} finally {
			applyingGroupFallDamage = false;
		}
		if (applied) LOGGER.info("The shared avatar landed; vanilla fall damage was applied to the group.");
		ServerPlayer deadPlayer = pendingGroupFallRestart;
		pendingGroupFallRestart = null;
		if (deadPlayer != null) {
			// One member of the group died, so the whole group goes down with them.
			killGroupMates(deadPlayer, fall);
			restartAfterDeath(deadPlayer);
		}
		return applied;
	}

	private static ServerPlayer currentController(Axis axis, MinecraftServer server) {
		UUID id = CONTROLLERS.get(axis);
		ServerPlayer player = id == null ? null : server.getPlayerList().getPlayer(id);
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

	private static void updateRoleHud(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendAxisRole(player);
		}
	}

	private static String axisKeyHint(Axis axis) {
		return switch (axis) {
			case X -> "D/A move X (east/west)";
			case Y -> "Space jumps; gravity controls Y";
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
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		ServerPlayNetworking.send(player, new AxisRolePayload(
			role,
			hudControllerName(Axis.X, server),
			hudControllerName(Axis.Y, server),
			hudControllerName(Axis.Z, server),
			!WAITING_FOR_RESPAWN.isEmpty()
		));
	}

	private static String hudControllerName(Axis axis, MinecraftServer server) {
		UUID id = CONTROLLERS.get(axis);
		if (id == null) return "open";
		ServerPlayer controller = server.getPlayerList().getPlayer(id);
		if (controller == null) return "offline";
		if (!controller.isAlive()) return "dead";
		if (controller.isSpectator()) return "paused";
		return controller.getGameProfile().name();
	}

	private static void restartAfterDeath(ServerPlayer deadPlayer) {
		if (deadPlayer.level().isClientSide()) return;
		MinecraftServer server = deadPlayer.level().getServer();
		if (server == null) return;
		if (restarting || !WAITING_FOR_RESPAWN.isEmpty()) {
			// The group is already waiting for its fallen members. Everybody who is down
			// now waits as well, the challenge continues when the last one is back.
			boolean added = WAITING_FOR_RESPAWN.add(deadPlayer.getUUID());
			for (ServerPlayer teammate : server.getPlayerList().getPlayers()) {
				if (!teammate.isAlive() && !teammate.isSpectator() && !teammate.isCreative()) {
					WAITING_FOR_RESPAWN.add(teammate.getUUID());
				}
			}
			if (added) {
				server.getPlayerList().broadcastSystemMessage(Component.literal(
					deadPlayer.getGameProfile().name() + " also died during the restart; the group remains paused."), false);
			}
			return;
		}
		restarting = true;

		ServerLevel startLevel = server.overworld();
		Vec3 start = safeSpawnPosition(startLevel);
		discardAvatarEntity();
		sharedAvatar = createAvatar(startLevel, start, tickCounter + 1);
		WAITING_FOR_RESPAWN.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CONTROLLER_DIMENSIONS.remove(player.getUUID());
			if (player.isSpectator() || (!player.isAlive() && player.isCreative())) continue;
			if (!player.isAlive()) {
				WAITING_FOR_RESPAWN.add(player.getUUID());
				continue;
			}
			player.teleportTo(startLevel, start.x, start.y, start.z, Set.of(), player.getYRot(), player.getXRot(), true);
			MOUNT_DEFERRED_THROUGH.put(player.getUUID(), tickCounter + 1);
			resetRunProgress(player);
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal(
			deadPlayer.getGameProfile().name() + " died. The shared run resets at world spawn." +
			(WAITING_FOR_RESPAWN.isEmpty() ? "" : " Waiting for fallen participants to respawn or disconnect.")), false);
		server.execute(() -> restarting = false);
	}

	private static void finishRestartRespawn(ServerPlayer player) {
		if (!WAITING_FOR_RESPAWN.remove(player.getUUID())) return;
		if (player.isSpectator()) {
			CONTROLLER_DIMENSIONS.remove(player.getUUID());
			updateRoleHud(player.level().getServer());
			player.sendSystemMessage(Component.literal(
				"You respawned as a spectator and are no longer part of the restart wait."));
			return;
		}
		resetRunProgress(player);
		if (sharedAvatar != null && sharedAvatar.onGround) {
			// Only move the player onto the carrier when it rests on the ground. A carrier
			// that is still falling would drop the player, and the vanilla fall damage of
			// that drop would kill a participant who just respawned.
			ServerLevel level = player.level().getServer().getLevel(sharedAvatar.dimension);
			if (level != null) {
				Vec3 pos = sharedAvatar.position;
				player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), true);
				MOUNT_DEFERRED_THROUGH.put(player.getUUID(), tickCounter + 1);
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
		int activePlayers = 0;
		float lowestHealth = Float.MAX_VALUE;
		int lowestFood = 20;
		float lowestSaturation = Float.MAX_VALUE;
		int lowestAir = Integer.MAX_VALUE;
		int longestFire = 0;
		for (ServerPlayer player : players) {
			if (player.isSpectator() || player.isCreative()) continue;
			activePlayers++;
			lowestHealth = Math.min(lowestHealth, player.getHealth());
			lowestFood = Math.min(lowestFood, player.getFoodData().getFoodLevel());
			lowestSaturation = Math.min(lowestSaturation, player.getFoodData().getSaturationLevel());
			lowestAir = Math.min(lowestAir, player.getAirSupply());
			longestFire = Math.max(longestFire, player.getRemainingFireTicks());
		}
		if (activePlayers == 0) return;
		sharedAvatar.health = lowestHealth;
		sharedAvatar.foodLevel = lowestFood;
		sharedAvatar.saturation = lowestSaturation;
		sharedAvatar.airSupply = lowestAir;
		sharedAvatar.fireTicks = longestFire;
		for (ServerPlayer player : players) {
			if (player.isSpectator() || player.isCreative()) continue;
			applySharedNeeds(player);
		}
	}

	private static void applySharedNeeds(ServerPlayer player) {
		if (sharedAvatar == null || player.isSpectator() || player.isCreative()) return;
		float health = Math.min(player.getMaxHealth(), sharedAvatar.health);
		if (Math.abs(player.getHealth() - health) > 0.001F) player.setHealth(health);
		if (player.getFoodData().getFoodLevel() != sharedAvatar.foodLevel) {
			player.getFoodData().setFoodLevel(sharedAvatar.foodLevel);
		}
		if (Math.abs(player.getFoodData().getSaturationLevel() - sharedAvatar.saturation) > 0.001F) {
			player.getFoodData().setSaturation(sharedAvatar.saturation);
		}
		if (player.getAirSupply() != sharedAvatar.airSupply) player.setAirSupply(sharedAvatar.airSupply);
		if (player.getRemainingFireTicks() != sharedAvatar.fireTicks) {
			player.setRemainingFireTicks(sharedAvatar.fireTicks);
		}
	}

	private static final class SharedAvatar {
		private ResourceKey<Level> dimension;
		private Vec3 position;
		private Vec3 velocity = Vec3.ZERO;
		private boolean onGround = true;
		private long createdAtTick;
		private SharedAvatarEntity entity;
		private float health = 20.0F;
		private int foodLevel = 20;
		private float saturation = 5.0F;
		private int airSupply = 300;
		private int fireTicks;
		private SharedAvatar(ResourceKey<Level> dimension) {
			this.dimension = dimension;
		}
	}

	private enum Axis {
		X("X"), Y("Y"), Z("Z");
		private final String label;
		Axis(String label) { this.label = label; }
		private static Axis parse(String value) {
			return switch (value.toLowerCase(Locale.ROOT)) {
				case "x" -> X;
				case "y" -> Y;
				case "z" -> Z;
				default -> null;
			};
		}
	}

	/**
	 * Called when a survival or adventure participant dies. The shared run ends for
	 * the other survival and adventure participants, then starts over.
	 */
	public static void onPlayerDeath(ServerPlayer player, DamageSource source) {
		if (!PARTICIPANTS.contains(player.getUUID()) || player.isCreative() || player.isSpectator()) return;
		if (applyingGroupFallDamage) {
			// The group fall damage is applied to everybody at once; the restart is
			// handled once the damage loop is finished.
			if (pendingGroupFallRestart == null) pendingGroupFallRestart = player;
			return;
		}
		if (applyingGroupDeath) return;
		killGroupMates(player, source);
		restartAfterDeath(player);
	}

	/**
	 * Takes every other survival or adventure participant down with the player that
	 * just died. They die from the same cause, so their death screens match.
	 *
	 * <p>The kill goes through {@link ServerPlayer#die(DamageSource)} instead of an
	 * enormous damage hit on purpose: damage can be absorbed by a totem of undying,
	 * which would leave a teammate alive after the group already restarted.
	 */
	private static void killGroupMates(ServerPlayer deadPlayer, DamageSource source) {
		MinecraftServer server = deadPlayer.level().getServer();
		if (server == null) return;
		// A fall can be lethal before any living teammate was damaged, in which case the
		// caller has no source to hand over. Fall back to an unsourced kill so the rest of
		// the group still goes down with its member.
		DamageSource cause = source != null ? source : damageSource(deadPlayer, DamageTypes.GENERIC_KILL);
		applyingGroupDeath = true;
		try {
			for (ServerPlayer teammate : server.getPlayerList().getPlayers()) {
				if (teammate == deadPlayer || !teammate.isAlive()
						|| teammate.isSpectator() || teammate.isCreative()) continue;
				if (!PARTICIPANTS.contains(teammate.getUUID())) continue;
				teammate.setHealth(0.0F);
				teammate.die(cause);
			}
		} finally {
			applyingGroupDeath = false;
		}
	}

	private static DamageSource damageSource(ServerPlayer player, ResourceKey<DamageType> type) {
		Registry<DamageType> damageTypes = player.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
		return new DamageSource(damageTypes.getOrThrow(type));
	}

	/** True while the group is taken down because one of its members died. */
	public static boolean isApplyingGroupDeath() {
		return applyingGroupDeath;
	}
}
