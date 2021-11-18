package me.neznamy.tab.platforms.krypton.features.unlimitedtags

import me.neznamy.tab.api.ArmorStand
import me.neznamy.tab.api.Property
import me.neznamy.tab.api.TabPlayer
import me.neznamy.tab.api.chat.EnumChatFormat
import me.neznamy.tab.api.chat.IChatBaseComponent
import me.neznamy.tab.shared.TabConstants
import me.neznamy.tab.shared.TAB
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.kryptonmc.api.entity.EntityTypes
import org.kryptonmc.api.entity.player.Player
import org.kryptonmc.api.registry.Registries
import org.kryptonmc.api.world.GameModes
import org.kryptonmc.krypton.entity.metadata.MetadataHolder
import org.kryptonmc.krypton.entity.metadata.MetadataKeys
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.packet.Packet
import org.kryptonmc.krypton.packet.out.play.PacketOutDestroyEntities
import org.kryptonmc.krypton.packet.out.play.PacketOutEntityTeleport
import org.kryptonmc.krypton.packet.out.play.PacketOutMetadata
import org.kryptonmc.krypton.packet.out.play.PacketOutSpawnLivingEntity
import org.spongepowered.math.vector.Vector2f
import org.spongepowered.math.vector.Vector3d
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class KryptonArmorStand(
    private val owner: TabPlayer,
    private val property: Property,
    private var yOffset: Double,
    private val staticOffset: Boolean
) : ArmorStand {

    private val manager = TAB.getInstance().featureManager.getFeature("nametagx") as NameTagX
    private val player = owner.player as Player
    private val entityId = ID_COUNTER.incrementAndGet()
    private val uuid = UUID.randomUUID()
    private var sneaking = false
    private var visible = calculateVisibility()
    private val destroyPacket = PacketOutDestroyEntities(entityId)

    val location: Vector3d
        get() {
            val x = player.location.x()
            var y = calculateY() + yOffset + 2
            val z = player.location.z()
            y -= if (player.sleepingPosition != null) 1.76 else if (sneaking) 0.45 else 0.18
            return Vector3d(x, y, z)
        }

    override fun refresh() {
        visible = calculateVisibility()
    }

    override fun getProperty(): Property = property

    override fun hasStaticOffset(): Boolean = staticOffset

    override fun getOffset(): Double = yOffset

    override fun setOffset(offset: Double) {
        if (yOffset == offset) return
        yOffset = offset
        owner.armorStandManager.nearbyPlayers.forEach { (it.player as KryptonPlayer).session.send(getTeleportPacket(it)) }
    }

    override fun spawn(viewer: TabPlayer) {
        val session = (viewer.player as KryptonPlayer).session
        getSpawnPackets(viewer).forEach(session::send)
    }

    override fun destroy() {
        TAB.getInstance().onlinePlayers.forEach { (it.player as KryptonPlayer).session.send(destroyPacket) }
    }

    override fun destroy(viewer: TabPlayer) {
        (viewer.player as KryptonPlayer).session.send(destroyPacket)
    }

    override fun teleport() {
        owner.armorStandManager.nearbyPlayers.forEach { (it.player as KryptonPlayer).session.send(getTeleportPacket(it)) }
    }

    override fun teleport(viewer: TabPlayer) {
        if (!owner.armorStandManager.isNearby(viewer) && viewer !== owner) {
            owner.armorStandManager.spawn(viewer)
            return
        }
        (viewer.player as KryptonPlayer).session.send(getTeleportPacket(viewer))
    }

    override fun sneak(sneaking: Boolean) {
        if (this.sneaking == sneaking) return // idk
        this.sneaking = sneaking
        owner.armorStandManager.nearbyPlayers.forEach {
            val session = (it.player as KryptonPlayer).session
            if (it.version.minorVersion == 14 && !TAB.getInstance().configuration.isArmorStandsAlwaysVisible) {
                if (sneaking) {
                    session.send(destroyPacket)
                } else {
                    spawn(it)
                }
                return@forEach
            }
            session.send(destroyPacket)
            val spawn = Runnable { spawn(it) }
            if (it.version.minorVersion == 8) {
                TAB.getInstance().cpuManager.runTaskLater(
                    50,
                    "compensating for 1.8.0 bugs",
                    manager,
                    TabConstants.CpuUsageCategory.V1_8_0_BUG_COMPENSATION,
                    spawn
                )
                return
            }
            spawn.run()
        }
    }

    override fun updateVisibility(force: Boolean) {
        val visibility = calculateVisibility()
        if (visible != visibility || force) {
            visible = visibility
            updateMetadata()
        }
    }

    override fun getEntityId(): Int = entityId

    private fun updateMetadata() {
        owner.armorStandManager.nearbyPlayers.forEach {
            val session = (it.player as KryptonPlayer).session
            session.send(PacketOutMetadata(entityId, createMetadata(property.getFormat(it), it).all))
        }
    }

    private fun getTeleportPacket(viewer: TabPlayer): PacketOutEntityTeleport =
        PacketOutEntityTeleport(entityId, armorStandLocationFor(viewer), Vector2f.ZERO, false)

    private fun getSpawnPackets(viewer: TabPlayer): Array<Packet> {
        visible = calculateVisibility()
        val data = createMetadata(property.getFormat(viewer), viewer)
        val location = armorStandLocationFor(viewer)
        return arrayOf(
            PacketOutSpawnLivingEntity(
                entityId,
                uuid,
                TYPE,
                location.x(),
                location.y(),
                location.z(),
                0F,
                0F,
                0F,
                0,
                0,
                0
            ),
            PacketOutMetadata(entityId, data.dirty)
        )
    }

    private fun createMetadata(displayName: String, viewer: TabPlayer): MetadataHolder {
        val holder = MetadataHolder(viewer.player as KryptonPlayer)

        var flags = 32 // invisible
        if (sneaking) flags += 2
        holder[MetadataKeys.FLAGS] = flags.toByte()
        holder[MetadataKeys.CUSTOM_NAME] = Optional.of(LegacyComponentSerializer.legacySection().deserialize(displayName))

        val visibility = if (isNameVisibilityEmpty(displayName) || manager.hasHiddenNametag(owner, viewer)) false else visible
        holder[MetadataKeys.CUSTOM_NAME_VISIBILITY] = visibility

        if (viewer.version.minorVersion > 8 || manager.markerFor18x) holder[MetadataKeys.ARMOR_STAND.FLAGS] = 16.toByte()
        return holder
    }

    private fun calculateVisibility(): Boolean {
        if (TAB.getInstance().configuration.isArmorStandsAlwaysVisible) return true
        return player.gameMode !== GameModes.SPECTATOR && !manager.hasHiddenNametag(owner) && property.get().isNotEmpty()
    }

    private fun calculateY(): Double {
        // TODO: Handle vehicles
        if (player.isSwimming || player.isFallFlying) return player.location.y() - 1.22
        return player.location.y()
    }

    private fun armorStandLocationFor(viewer: TabPlayer): Vector3d {
        if (viewer.version.minorVersion == 8 && !manager.markerFor18x) return location.add(0.0, -2.0, 0.0)
        return location
    }

    private fun isNameVisibilityEmpty(displayName: String): Boolean {
        if (displayName.isEmpty()) return true
        if (!displayName.startsWith(EnumChatFormat.COLOR_CHAR) && !displayName.startsWith('&') && !displayName.startsWith('#')) return false
        var text = IChatBaseComponent.fromColoredText(displayName).toRawText()
        if (text.contains(' ')) text = text.replace(" ", "")
        return text.isEmpty()
    }

    override fun respawn(viewer: TabPlayer) {
        //TODO
    }

    companion object {

        private val ID_COUNTER = AtomicInteger(2000000000)
        private val TYPE = Registries.ENTITY_TYPE.idOf(EntityTypes.ARMOR_STAND)
    }
}