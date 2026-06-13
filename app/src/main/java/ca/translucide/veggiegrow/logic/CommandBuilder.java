package ca.translucide.veggiegrow.logic;

import java.util.Locale;

import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;

/**
 * Builds the controller upload payload from the current watering rates, e.g.
 * {@code "A1:0.09,A2:0.08"}. These instructions persist on the controller until the next upload.
 *
 * <p>The wire format is isolated here so it can be changed in one place.
 */
public final class CommandBuilder {

    /** Bins with a current rate of 0 are omitted by default. */
    public static final boolean OMIT_ZERO_RATES = true;

    private CommandBuilder() {
    }

    /** Builds the full payload across all spaces/bins for the given moment. */
    public static String buildPayload(AppData data, long nowMillis) {
        StringBuilder sb = new StringBuilder();
        for (GrowthSpace space : data.spaces) {
            for (Bin bin : space.bins) {
                double rate = GrowthCalculator.currentWateringRate(bin, nowMillis);
                if (OMIT_ZERO_RATES && rate <= 0d) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(AlertEngine.binReference(space, bin))
                        .append(':')
                        .append(formatRate(rate));
            }
        }
        return sb.toString();
    }

    /** Two-decimal fixed format, matching the "A1:0.09" example. */
    public static String formatRate(double rate) {
        return String.format(Locale.US, "%.2f", rate);
    }
}
