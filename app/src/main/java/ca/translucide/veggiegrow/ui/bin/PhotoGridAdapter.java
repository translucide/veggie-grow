package ca.translucide.veggiegrow.ui.bin;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.databinding.ItemPhotoBinding;
import ca.translucide.veggiegrow.imagesearch.ImageResult;
import ca.translucide.veggiegrow.util.ThumbnailLoader;

public class PhotoGridAdapter extends RecyclerView.Adapter<PhotoGridAdapter.VH> {

    public interface Listener {
        void onPick(ImageResult result);
    }

    private final List<ImageResult> items = new ArrayList<>();
    private final ThumbnailLoader loader;
    private final Listener listener;

    public PhotoGridAdapter(ThumbnailLoader loader, Listener listener) {
        this.loader = loader;
        this.listener = listener;
    }

    public void submit(List<ImageResult> results) {
        items.clear();
        items.addAll(results);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPhotoBinding b = ItemPhotoBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ImageResult r = items.get(position);
        loader.load(r.thumbUrl, h.b.photo, R.drawable.ic_leaf);
        h.b.photo.setContentDescription(r.title);
        h.itemView.setOnClickListener(v -> listener.onPick(r));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemPhotoBinding b;

        VH(ItemPhotoBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
