package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.GrimMath;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class PhaseCollisionResolver {
    private PhaseCollisionResolver() {
    }

    static @Nullable Source resolve(
            GrimPlayer player,
            SimpleCollisionBox target,
            int searchPaddingBlocks,
            double matchEpsilon
    ) {
        int minX = GrimMath.floor(target.minX) - searchPaddingBlocks;
        int maxX = GrimMath.floor(target.maxX) + searchPaddingBlocks;

        int minY = Math.max(
                player.compensatedWorld.getMinHeight(),
                GrimMath.floor(target.minY) - searchPaddingBlocks
        );
        int maxY = Math.min(
                player.compensatedWorld.getMaxHeight() - 1,
                GrimMath.floor(target.maxY) + searchPaddingBlocks
        );

        int minZ = GrimMath.floor(target.minZ) - searchPaddingBlocks;
        int maxZ = GrimMath.floor(target.maxZ) + searchPaddingBlocks;

        List<SimpleCollisionBox> downcast = new ArrayList<>(16);

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    WrappedBlockState state = player.compensatedWorld.getBlock(x, y, z);

                    if (state.getGlobalId() == 0) {
                        continue;
                    }

                    CollisionBox collision = CollisionData.getData(state.getType())
                            .getMovementCollisionBox(
                                    player,
                                    player.getClientVersion(),
                                    state,
                                    x,
                                    y,
                                    z
                            );

                    downcast.clear();
                    collision.downCast(downcast);

                    for (SimpleCollisionBox candidate : downcast) {
                        if (sameBox(target, candidate, matchEpsilon)) {
                            return new Source(state, x, y, z);
                        }
                    }
                }
            }
        }

        return null;
    }

    private static boolean sameBox(
            SimpleCollisionBox first,
            SimpleCollisionBox second,
            double epsilon
    ) {
        return Math.abs(first.minX - second.minX) <= epsilon
                && Math.abs(first.minY - second.minY) <= epsilon
                && Math.abs(first.minZ - second.minZ) <= epsilon
                && Math.abs(first.maxX - second.maxX) <= epsilon
                && Math.abs(first.maxY - second.maxY) <= epsilon
                && Math.abs(first.maxZ - second.maxZ) <= epsilon;
    }

    record Source(WrappedBlockState state, int x, int y, int z) {
    }
}
