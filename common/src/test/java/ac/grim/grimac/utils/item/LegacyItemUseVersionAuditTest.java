package ac.grim.grimac.utils.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyItemUseVersionAuditTest {

    @Test
    void bukkit18UsesTheRealLegacyStopUsePath() throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/manager/BukkitItemResetHandler.java"
        ));

        assertTrue(source.contains("case \"v1_8_R3\" -> \"bS\""));
        assertTrue(source.contains("case \"v1_8_R3\" -> \"bV\""));
        assertTrue(source.contains("boolean wasUsingItem = isUsingItem.test(player)"));
        assertTrue(source.contains("if (wasUsingItem) player.updateInventory()"));
    }

    @Test
    void legacyClientsDoNotDependOnModernActiveHandMetadata() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/events/packets/PacketSelfMetadataListener.java"
        ));

        assertTrue(source.contains(
                "player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)"
        ));
        assertTrue(source.contains("confirmServerActive("));
    }

    @Test
    void incomingVelocityDoesNotOwnLegacyItemUseReset() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        ));

        assertFalse(source.contains("resetItemUsage("));
        assertFalse(source.contains("requestServerReset("));
    }

    @Test
    void fastConsumeClassificationDoesNotTreatChargeItemsAsConsumables()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/utils/item/ItemUseSessionTracker.java"
        ));

        assertTrue(source.contains(
                "type.hasAttribute(ItemTypes.ItemAttribute.EDIBLE)"
        ));
        assertTrue(source.contains("type == ItemTypes.POTION"));
        assertTrue(source.contains("type == ItemTypes.MILK_BUCKET"));
        assertFalse(source.contains("type == ItemTypes.BOW"));
        assertFalse(source.contains("type == ItemTypes.CROSSBOW"));
        assertFalse(source.contains("type == ItemTypes.TRIDENT"));
        assertFalse(source.contains("type == ItemTypes.SHIELD"));
    }

    @Test
    void fastConsumeDurationPolicyCoversLegacyAndModernConsumables()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/utils/item/ItemUseSessionTracker.java"
        ));

        assertTrue(source.contains("ComponentTypes.CONSUMABLE"));
        assertTrue(source.contains("getConsumeSeconds() * 1000.0F"));
        assertTrue(source.contains("type == ItemTypes.POTION"));
        assertTrue(source.contains("type == ItemTypes.MILK_BUCKET"));
        assertTrue(source.contains("type == ItemTypes.HONEY_BOTTLE"));
        assertTrue(source.contains("type == ItemTypes.SUSPICIOUS_STEW"));
        assertTrue(source.contains("type == ItemTypes.DRIED_KELP) return 800L"));
        assertTrue(source.contains("type == ItemTypes.HONEY_BOTTLE) return 2000L"));
        assertTrue(source.contains("return 1600L"));
    }

}
