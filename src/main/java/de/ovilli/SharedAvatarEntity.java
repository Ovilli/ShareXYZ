package de.ovilli;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible carrier entity that acts as the shared avatar.
 *
 * <p>The shared avatar used to be "one of the players": the server moved that player and
 * copied the position to everybody else. That created two writers for a single position and
 * made the clients fight the server over the local player position, which is the classic
 * source of rubber-banding in modern Minecraft (the client owns its own player entity and
 * simply ignores the server's position for it).
 *
 * <p>This entity removes the ambiguity completely:
 * <ul>
 *   <li>the server owns exactly one position - the one of this entity,</li>
 *   <li>every participant rides it, and vanilla derives a passenger's position from its
 *       vehicle on both the client and the server,</li>
 *   <li>vanilla ignores the position a passenger reports to the server, so the client can
 *       never contradict the server.</li>
 * </ul>
 */
public class SharedAvatarEntity extends Entity {
	/**
	 * Number of participants that fit onto the shared avatar. There are only three axes, but
	 * extra players still share the avatar, so there is room for a whole group.
	 */
	public static final int MAX_PASSENGERS = 16;
	private static final double PLAYER_MOUNT_HEIGHT = 0.5;

	public static final Identifier IDENTIFIER = Identifier.fromNamespaceAndPath(ShareXYZ.MOD_ID, "shared_avatar");
	public static final ResourceKey<EntityType<?>> TYPE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, IDENTIFIER);

	public static final EntityType<SharedAvatarEntity> TYPE = EntityType.Builder
		.of(SharedAvatarEntity::new, MobCategory.MISC)
		.sized(0.6F, 1.8F)
		.noSummon()
		.noSave()
		.fireImmune()
		.clientTrackingRange(8)
		.updateInterval(1)
		.build(TYPE_KEY);

	public SharedAvatarEntity(EntityType<SharedAvatarEntity> type, Level level) {
		super(type, level);
		this.setNoGravity(true);
		this.setInvisible(true);
		this.setSilent(true);
		this.setPermanentlyInvulnerable(true);
	}

	/**
	 * Registers the entity type. Must run on both the client and the server, otherwise the
	 * client cannot resolve the entity type of the spawn packet.
	 */
	public static EntityType<SharedAvatarEntity> register() {
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, IDENTIFIER, TYPE);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		// The shared flags (invisible, no gravity, ...) are already defined by Entity itself.
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	/**
	 * The carrier detects the shared fall, then asks each participant's player entity to
	 * apply vanilla fall damage. This preserves player-specific protection such as armor
	 * enchantments and status effects.
	 *
	 * <p>The server calls
	 * {@link Entity#doCheckFallDamage(double, double, double, boolean)} for the carrier after
	 * moving it, which accumulates the fall distance and lets the landing block pass its own
	 * damage multiplier to this method (hay bale, slime block, bed, honey block, ...).
	 *
	 * <p>The carrier itself is not a {@code LivingEntity}, so this override forwards the fall
	 * distance and multiplier to the participants' vanilla player fall-damage handling.
	 */
	@Override
	public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
		return ShareXYZ.onSharedAvatarFell(fallDistance, damageMultiplier, source);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		// The shared avatar is never persisted.
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		// The shared avatar is never persisted.
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(Entity entity) {
		return false;
	}

	/**
	 * The carrier owns the position of the whole group. Nothing else is allowed to shove it
	 * around: not other entities, not water currents, and not pistons.
	 */
	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE_ENTITY;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return passenger instanceof Player player
			// The participant set only exists on the server. Clients must accept the
			// passenger list synchronized by the server in order to render the mount.
			&& (this.level().isClientSide() || ShareXYZ.isChallengeParticipant(player.getUUID()))
			&& player.isAlive()
			&& !player.isSpectator()
			&& this.couldAcceptPassenger();
	}

	@Override
	protected boolean canRide(Entity vehicle) {
		return false;
	}

	@Override
	protected boolean couldAcceptPassenger() {
		return this.getPassengers().size() < MAX_PASSENGERS;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// Lift the riders slightly so their camera and head do not sit close to the ground.
		return new Vec3(0.0, PLAYER_MOUNT_HEIGHT * scale, 0.0);
	}
}
