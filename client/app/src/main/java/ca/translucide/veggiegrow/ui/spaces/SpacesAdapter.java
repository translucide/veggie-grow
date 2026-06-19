package ca.translucide.veggiegrow.ui.spaces;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.databinding.ItemSpaceExpandedBinding;
import ca.translucide.veggiegrow.logic.GrowthCalculator;
import ca.translucide.veggiegrow.logic.Units;
import ca.translucide.veggiegrow.util.ImageUtils;

/**
 * Rack (growth space) list. Each rack shows a wrapping grid of its bins so any bin is reachable in
 * one tap. The number of bins previewed adapts to how many racks there are so the list fills the
 * screen: with fewer than {@link #ALL_BINS_BELOW_RACKS} racks every bin is shown; with more, only
 * {@link #PREVIEW_BIN_COUNT} bins are previewed per rack (tap the rack to see the rest).
 */
public class SpacesAdapter extends RecyclerView.Adapter<SpacesAdapter.ExpandedVH> {

    /** Below this many racks, show every bin in each rack; at/above it, preview a few. */
    public static final int ALL_BINS_BELOW_RACKS = 5;
    public static final int PREVIEW_BIN_COUNT = 4;
    private static final int GRID_SPAN = 4;

    public interface Listener {
        void onClick(GrowthSpace space);

        void onLongClick(GrowthSpace space);

        void onBinClick(GrowthSpace space, Bin bin);
    }

    private final List<GrowthSpace> items = new ArrayList<>();
    private Settings settings = new Settings();
    private long now;
    private int binLimit = Integer.MAX_VALUE;
    private final Listener listener;

    public SpacesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<GrowthSpace> spaces, Settings settings, long now) {
        this.items.clear();
        this.items.addAll(spaces);
        this.settings = settings;
        this.now = now;
        this.binLimit = spaces.size() < ALL_BINS_BELOW_RACKS ? Integer.MAX_VALUE : PREVIEW_BIN_COUNT;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ExpandedVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSpaceExpandedBinding b = ItemSpaceExpandedBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ExpandedVH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull ExpandedVH holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // --- shared helpers ------------------------------------------------------------------------

    private boolean hasAlert(GrowthSpace s) {
        if (s.waterReservoirSize > 0 && GrowthCalculator.isReservoirLow(s, settings, now)) {
            return true;
        }
        for (Bin b : s.bins) {
            if (GrowthCalculator.isHarvestDue(b, now, settings.harvestAlertDays)) {
                return true;
            }
        }
        return false;
    }

    private String label(GrowthSpace s) {
        String name = s.name == null ? "" : s.name.trim();
        return name.isEmpty() ? s.code : s.code + " — " + name;
    }

    private String reservoirText(View v, GrowthSpace s) {
        double remaining = GrowthCalculator.estimatedReservoirRemaining(s, now);
        return v.getContext().getString(R.string.reservoir_remaining,
                Units.num(Units.volumeToDisplay(remaining, settings.pumpRateUnit)),
                Units.formatVolume(s.waterReservoirSize, settings.pumpRateUnit));
    }

    // --- view holder ---------------------------------------------------------------------------

    class ExpandedVH extends RecyclerView.ViewHolder {
        final ItemSpaceExpandedBinding b;
        final BinThumbAdapter binAdapter;
        GrowthSpace boundSpace;

        ExpandedVH(ItemSpaceExpandedBinding b) {
            super(b.getRoot());
            this.b = b;
            binAdapter = new BinThumbAdapter(bin -> {
                if (boundSpace != null) listener.onBinClick(boundSpace, bin);
            });
            b.binsRecycler.setLayoutManager(new GridLayoutManager(b.getRoot().getContext(), GRID_SPAN));
            b.binsRecycler.setNestedScrollingEnabled(false);
            b.binsRecycler.setAdapter(binAdapter);
        }

        void bind(GrowthSpace s) {
            boundSpace = s;
            b.title.setText(label(s));
            b.reservoir.setText(reservoirText(itemView, s));
            b.alertBadge.setVisibility(hasAlert(s) ? View.VISIBLE : View.GONE);

            Bitmap bmp = ImageUtils.base64ToBitmap(s.imageBase64);
            if (bmp != null) b.image.setImageBitmap(bmp);
            else b.image.setImageResource(R.drawable.ic_leaf);

            boolean hasBins = !s.bins.isEmpty();
            b.binsRecycler.setVisibility(hasBins ? View.VISIBLE : View.GONE);
            b.binsEmpty.setVisibility(hasBins ? View.GONE : View.VISIBLE);

            List<Bin> shown = s.bins;
            if (s.bins.size() > binLimit) {
                shown = new ArrayList<>(s.bins.subList(0, binLimit));
            }
            binAdapter.submit(s.code, shown, settings, now);

            b.header.setOnClickListener(v -> listener.onClick(s));
            b.header.setOnLongClickListener(v -> {
                listener.onLongClick(s);
                return true;
            });
        }
    }
}
