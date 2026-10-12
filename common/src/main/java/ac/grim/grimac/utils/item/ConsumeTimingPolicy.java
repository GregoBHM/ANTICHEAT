package ac.grim.grimac.utils.item;

public final class ConsumeTimingPolicy {
    private ConsumeTimingPolicy() {
    }

    public static Result evaluate(
            long expectedMillis,
            long actualMillis,
            double minimumRatio,
            long absoluteLenienceMillis,
            long transactionPingMillis,
            double confidence,
            double minimumEvidenceConfidence
    ) {
        long pingLenience = Math.min(
                180L,
                Math.max(0L, transactionPingMillis) / 4L
        );
        long lenience = Math.max(0L, absoluteLenienceMillis) + pingLenience;
        long minimumAllowed = Math.max(
                0L,
                Math.round(Math.max(0L, expectedMillis) * minimumRatio)
                        - lenience
        );

        boolean timingImpossible = actualMillis < minimumAllowed;
        boolean confident = confidence >= minimumEvidenceConfidence;

        return new Result(
                timingImpossible && confident,
                minimumAllowed,
                pingLenience
        );
    }

    public record Result(
            boolean violation,
            long minimumAllowedMillis,
            long pingLenienceMillis
    ) {
    }
}
