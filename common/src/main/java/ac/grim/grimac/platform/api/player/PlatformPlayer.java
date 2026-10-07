package ac.grim.grimac.platform.api.player;

import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.sender.Sender;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3d;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public interface PlatformPlayer extends GrimEntity, OfflinePlatformPlayer {
    void kickPlayer(String textReason);

    void resyncSharedFlags();

    boolean hasPermission(String s);

    boolean hasPermission(String s, boolean defaultIfUnset);

    void sendMessage(String message);

    void sendMessage(Component message);

    void updateInventory();

    Vector3d getPosition();

    PlatformInventory getInventory();

    @Nullable GrimEntity getVehicle();

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    /** Platform fall-distance bridge used by FallIntegrity. */
    default float getFallDistance() {
        return 0.0F;
    }

    /** Platform fall-distance bridge used by FallIntegrity. */
    default void setFallDistance(float fallDistance) {
        // Optional on non-Bukkit platforms.
    }

    boolean isExternalPlayer();

    void sendPluginMessage(String channelName, byte[] byteArray);

    Sender getSender();

    /*
     * Replaces native player reference in PlatformPlayer implementation with a new object
     * Vanilla MC replaces ServerPlayerEntity references on respawn and dimension change
     */
    default void replaceNativePlayer(Object nativePlayerObject) {}

    BlockTranslator getBlockTranslator();
}
