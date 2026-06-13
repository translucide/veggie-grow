package ca.translucide.veggiegrow.ui.alerts;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.databinding.ItemAlertBinding;
import ca.translucide.veggiegrow.logic.Alert;

public class AlertAdapter extends RecyclerView.Adapter<AlertAdapter.VH> {

    private final List<Alert> items = new ArrayList<>();

    public void submit(List<Alert> alerts) {
        items.clear();
        items.addAll(alerts);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAlertBinding b = ItemAlertBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Alert a = items.get(position);
        h.b.title.setText(a.title);
        h.b.message.setText(a.message);
        h.b.icon.setImageResource(a.type == Alert.Type.RESERVOIR
                ? R.drawable.ic_upload : R.drawable.ic_alert);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemAlertBinding b;

        VH(ItemAlertBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
