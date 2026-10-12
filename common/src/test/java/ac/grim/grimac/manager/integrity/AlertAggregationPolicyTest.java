package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertAggregationPolicyTest {

    @Test
    void componentsDoNotCreateNewNormalAlertBucketsByDefault() {
        assertEquals(
                "grim.movement.noslow",
                AlertAggregationManager.aggregationBucket(
                        "grim.movement.noslow",
                        "noslow.cobweb",
                        false
                )
        );
        assertEquals(
                "grim.movement.noslow",
                AlertAggregationManager.aggregationBucket(
                        "grim.movement.noslow",
                        "noslow.velocity",
                        false
                )
        );
    }

    @Test
    void administratorsCanOptIntoComponentBuckets() {
        assertEquals(
                "grim.movement.noslow#noslow.cobweb",
                AlertAggregationManager.aggregationBucket(
                        "grim.movement.noslow",
                        "NoSlow.Cobweb",
                        true
                )
        );
    }

    @Test
    void reloadClearsPresentationStateWhenBucketSemanticsChange()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/integrity/AlertAggregationManager.java"
        ));

        int start = source.indexOf("public void reload(");
        int end = source.indexOf("public @NotNull Decision evaluate(", start);
        assertTrue(start >= 0 && end > start);

        String reload = source.substring(start, end);
        assertTrue(reload.contains(
                "componentBuckets = config.getBooleanElse"
        ));
        assertTrue(reload.contains("states.clear()"));
    }
}
