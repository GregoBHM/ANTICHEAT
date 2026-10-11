package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
