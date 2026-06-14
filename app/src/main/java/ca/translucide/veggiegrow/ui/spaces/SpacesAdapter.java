package ca.translucide.veggiegrow.ui.spaces;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.databinding.ItemSpaceBinding;
import ca.translucide.veggiegrow.databinding.ItemSpaceExpandedBinding;
import ca.translucide.veggiegrow.logic.GrowthCalculator;
import ca.translucide.veggiegrow.logic.Units;
import ca.translucide.veggiegrow.util.ImageUtils;

/**
 * Rack (growth space) list. When there are fewer than {@link #EXPANDED_MAX_SPACES} racks, each rack
 * is shown expanded with a horizontal gallery of its bins so any bin is reachable in one tap.
 * Otherwise a compact one-line-per-rack list is used.
 */
public class SpacesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Below this many racks, show the expanded bin galleries. */
    public static final int EXPANDED_MAX_SPACES = 10;

    private static final int TYPE_COMPACT = 0;
    private static final int TYPE_EXPANDED = 1;

    public interface Listener {
        void onClick(GrowthSpace space);

        void onLongClick(GrowthSpace space);

        void onBinClick(GrowthSpace space, Bin bin);
    }

    private final List<GrowthSpace> items = new ArrayList<>();
    private Settings settings = new Settings();
    private long now;
    private boolean expanded;
    private final Listener listener;

    public SpacesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<GrowthSpace> spaces, Settings settings, long now) {
        this.items.clear();
        this.items.addAll(spaces);
        this.settings = settings;
        this.now = now;
        this.expanded = spaces.size() < EXPANDED_MAX_SPACES;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return expanded ? TYPE_EXPANDED : TYPE_COMPACT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_EXPANDED) {
            return new ExpandedVH(ItemSpaceExpandedBinding.inflate(inflater, parent, false));
        }
        return new CompactVH(ItemSpaceBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        GrowthSpace s = items.get(position);
        if (holder instanceof ExpandedVH) {
            ((ExpandedVH) holder).bind(s);
        } else {
            ((CompactVH) holder).bind(s);
        }
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

    // --- view holders --------------------------------------------------------------------------

    class CompactVH extends RecyclerView.ViewHolder {
        final ItemSpaceBinding b;

        CompactVH(ItemSpaceBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(GrowthSpace s) {
            b.title.setText(label(s));
            b.subtitle.setText(itemView.getContext().getString(R.string.bins_count, s.bins.size()));
            b.reservoir.setText(reservoirText(itemView, s));
            b.alertBadge.setVisibility(hasAlert(s) ? View.VISIBLE : View.GONE);

            Bitmap bmp = ImageUtils.base64ToBitmap(s.imageBase64);
            if (bmp != null) b.image.setImageBitmap(bmp);
            else b.image.setImageResource(R.drawable.ic_leaf);

            itemView.setOnClickListener(v -> listener.onClick(s));
            itemView.setOnLongClickListener(v -> {
                listener.onLongClick(s);
                return true;
            });
        }
    }

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
            b.binsRecycler.setLayoutManager(new LinearLayoutManager(
                    b.getRoot().getContext(), LinearLayoutManager.HORIZONTAL, false));
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
            binAdapter.submit(s.code, s.bins, settings, now);

            b.header.setOnClickListener(v -> listener.onClick(s));
            b.header.setOnLongClickListener(v -> {
                listener.onLongClick(s);
                return true;
            });
        }
    }
}
