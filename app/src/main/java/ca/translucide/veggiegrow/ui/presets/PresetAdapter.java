package ca.translucide.veggiegrow.ui.presets;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.databinding.ItemPresetBinding;

public class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.VH> {

    public interface Listener {
        void onLongClick(Preset preset);
    }

    private final List<Preset> items = new ArrayList<>();
    private final Listener listener;

    public PresetAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Preset> presets) {
        items.clear();
        items.addAll(presets);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPresetBinding b = ItemPresetBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Preset p = items.get(position);
        h.b.title.setText(p.name);
        int points = p.wateringSchedule == null ? 0 : p.wateringSchedule.size();
        String variety = p.varietyName == null || p.varietyName.isEmpty() ? "—" : p.varietyName;
        h.b.subtitle.setText(variety + " · " + points + " rate point(s) · harvest " + p.firstHarvestDays + "d");
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(p);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemPresetBinding b;

        VH(ItemPresetBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
