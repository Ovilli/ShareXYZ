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
import net.minecraft.world.level.Level;
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
	public boolean canBeCollidedWith(Entity entity) {
		return false;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().size() < MAX_PASSENGERS;
	}

	@Override
	protected boolean canRide(Entity passenger) {
		return true;
	}

	@Override
	protected boolean couldAcceptPassenger() {
		return true;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// Spread the participants a little bit so they do not render exactly on top of each other.
		int index = this.getPassengers().indexOf(passenger);
		if (index < 0) return Vec3.ZERO;
		return switch (index % 3) {
			case 0 -> Vec3.ZERO;
			case 1 -> new Vec3(0.45, 0.0, 0.0);
			default -> new Vec3(-0.45, 0.0, 0.0);
		};
	}
}
