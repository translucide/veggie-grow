package ca.translucide.veggiegrow.ui.spaces;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.databinding.ItemSpaceBinding;
import ca.translucide.veggiegrow.logic.GrowthCalculator;
import ca.translucide.veggiegrow.util.ImageUtils;

public class SpacesAdapter extends RecyclerView.Adapter<SpacesAdapter.VH> {

    public interface Listener {
        void onClick(GrowthSpace space);

        void onLongClick(GrowthSpace space);
    }

    private final List<GrowthSpace> items = new ArrayList<>();
    private Settings settings = new Settings();
    private long now;
    private final Listener listener;

    public SpacesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<GrowthSpace> spaces, Settings settings, long now) {
        this.items.clear();
        this.items.addAll(spaces);
        this.settings = settings;
        this.now = now;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSpaceBinding b = ItemSpaceBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GrowthSpace s = items.get(position);
        h.b.title.setText(label(s));
        h.b.subtitle.setText(h.itemView.getContext()
                .getString(R.string.bins_count, s.bins.size()));

        double remaining = GrowthCalculator.estimatedReservoirRemaining(s, now);
        h.b.reservoir.setText(h.itemView.getContext().getString(
                R.string.reservoir_remaining, round1(remaining), round1(s.waterReservoirSize)));

        boolean alert = hasAlert(s);
        h.b.alertBadge.setVisibility(alert ? android.view.View.VISIBLE : android.view.View.GONE);

        Bitmap bmp = ImageUtils.base64ToBitmap(s.imageBase64);
        if (bmp != null) {
            h.b.image.setImageBitmap(bmp);
        } else {
            h.b.image.setImageResource(R.drawable.ic_leaf);
        }

        h.itemView.setOnClickListener(v -> listener.onClick(s));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(s);
            return true;
        });
    }

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

    private double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemSpaceBinding b;

        VH(ItemSpaceBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
