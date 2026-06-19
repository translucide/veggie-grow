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
import ca.translucide.veggiegrow.databinding.ItemBinThumbBinding;
import ca.translucide.veggiegrow.logic.GrowthCalculator;
import ca.translucide.veggiegrow.util.ImageUtils;

/**
 * Horizontal gallery of bins shown inside an expanded rack card. Tapping a thumbnail opens that bin
 * directly.
 */
public class BinThumbAdapter extends RecyclerView.Adapter<BinThumbAdapter.VH> {

    public interface Listener {
        void onBinClick(Bin bin);
    }

    private final List<Bin> items = new ArrayList<>();
    private String spaceCode = "";
    private Settings settings = new Settings();
    private long now;
    private final Listener listener;

    public BinThumbAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(String spaceCode, List<Bin> bins, Settings settings, long now) {
        this.spaceCode = spaceCode;
        this.settings = settings;
        this.now = now;
        items.clear();
        items.addAll(bins);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBinThumbBinding b = ItemBinThumbBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Bin bin = items.get(position);
        h.b.code.setText(spaceCode + bin.code);
        String variety = bin.varietyName == null ? "" : bin.varietyName.trim();
        h.b.variety.setText(variety);
        h.b.variety.setVisibility(variety.isEmpty() ? View.GONE : View.VISIBLE);

        boolean due = GrowthCalculator.isHarvestDue(bin, now, settings.harvestAlertDays);
        h.b.alertBadge.setVisibility(due ? View.VISIBLE : View.GONE);

        Bitmap bmp = ImageUtils.base64ToBitmap(bin.imageBase64);
        if (bmp != null) {
            h.b.photo.setImageBitmap(bmp);
        } else {
            h.b.photo.setImageResource(R.drawable.ic_leaf);
        }

        h.itemView.setOnClickListener(v -> listener.onBinClick(bin));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemBinThumbBinding b;

        VH(ItemBinThumbBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
