package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import ac.grim.grimac.utils.math.GrimMath;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "Phase", stableKey = "grim.prediction.phase", description = "Moved into a solid block during movement prediction", setback = 1, decay = 0.005)
public class Phase extends Check implements PostPredictionListener {
    private SimpleCollisionBox oldBB;
    private int recentBlockGraceTicks = 4;

    public Phase(GrimPlayer player) {
        super(player);
        oldBB = player.boundingBox;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!player.getSetbackTeleportUtil().blockOffsets && !predictionComplete.getData().isTeleport() && predictionComplete.isChecked()) { // Not falling through world
            SimpleCollisionBox newBB = player.boundingBox;

            List<SimpleCollisionBox> boxes = new ArrayList<>();
            Collisions.getCollisionBoxes(player, newBB, boxes, false);

            for (SimpleCollisionBox box : boxes) {
                if (newBB.isIntersected(box) && !oldBB.isIntersected(box)) {
                    int blockX = GrimMath.floor((box.minX + box.maxX) * 0.5D);
                    int blockY = GrimMath.floor((box.minY + box.maxY) * 0.5D);
                    int blockZ = GrimMath.floor((box.minZ + box.maxZ) * 0.5D);
                    if (recentBlockGraceTicks > 0 && player.blockHistory.hasRecentModification(
                            blockX, blockY, blockZ, GrimAPI.INSTANCE.getTickManager().currentTick, recentBlockGraceTicks)) {
                        // The compensated world is still converging after a real block change. Do not turn this into Phase evidence.
                        continue;
                    }
                    if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
                        // A bit of a hacky way to get the block state, but this is much faster to use the tuinity method for grabbing collision boxes
                        WrappedBlockState state = player.compensatedWorld.getBlock((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
                        if (BlockTags.ANVIL.contains(state.getType()) || state.getType() == StateTypes.CHEST || state.getType() == StateTypes.TRAPPED_CHEST) {
                            continue; // 1.8 glitchy block, ignore
                        }
                    }
                    double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                            .record(player.uuid, IntegritySignal.PHASE);
                    String environment = GrimAPI.INSTANCE.getEnvironmentContextManager().summary(player);
                    flagWithSetback("corr=" + String.format("%.2f", correlation) + " env=" + environment);
                    return;
                }
            }
        }

        oldBB = player.boundingBox;
        reward();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        recentBlockGraceTicks = Math.max(0, Math.min(20,
                config.getIntElse("world-integrity.phase-recent-block-grace-ticks", 4)));
    }
}
