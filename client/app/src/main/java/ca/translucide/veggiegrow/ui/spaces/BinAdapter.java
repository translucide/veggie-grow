package ca.translucide.veggiegrow.ui.spaces;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.databinding.ItemBinBinding;
import ca.translucide.veggiegrow.logic.GrowthCalculator;
import ca.translucide.veggiegrow.logic.Units;
import ca.translucide.veggiegrow.util.DateUtils;
import ca.translucide.veggiegrow.util.ImageUtils;

public class BinAdapter extends RecyclerView.Adapter<BinAdapter.VH> {

    public interface Listener {
        void onClick(Bin bin);

        void onLongClick(Bin bin);
    }

    private final List<Bin> items = new ArrayList<>();
    private String spaceCode = "";
    private Settings settings = new Settings();
    private long now;
    private final Listener listener;

    public BinAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(String spaceCode, List<Bin> bins, Settings settings, long now) {
        this.spaceCode = spaceCode;
        this.items.clear();
        this.items.addAll(bins);
        this.settings = settings;
        this.now = now;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBinBinding b = ItemBinBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Bin bin = items.get(position);
        String ref = spaceCode + bin.code;
        String variety = bin.varietyName == null ? "" : bin.varietyName.trim();
        h.b.title.setText(variety.isEmpty() ? ref : ref + " — " + variety);

        double rate = GrowthCalculator.currentWateringRate(bin, now);
        h.b.rate.setText(h.itemView.getContext()
                .getString(R.string.current_rate, Units.formatRate(rate, settings.pumpRateUnit)));

        long next = GrowthCalculator.nextHarvestDate(bin, now);
        if (next == GrowthCalculator.NO_HARVEST) {
            h.b.harvest.setText("Next harvest: —");
        } else {
            h.b.harvest.setText("Next harvest: " + DateUtils.format(next));
        }

        boolean due = GrowthCalculator.isHarvestDue(bin, now, settings.harvestAlertDays);
        h.b.alertBadge.setVisibility(due ? View.VISIBLE : View.GONE);

        Bitmap bmp = ImageUtils.base64ToBitmap(bin.imageBase64);
        if (bmp != null) {
            h.b.image.setImageBitmap(bmp);
        } else {
            h.b.image.setImageResource(R.drawable.ic_leaf);
        }

        h.itemView.setOnClickListener(v -> listener.onClick(bin));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(bin);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemBinBinding b;

        VH(ItemBinBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
