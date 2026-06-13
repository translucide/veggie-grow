package ca.translucide.veggiegrow.ui.bin;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.GridLayoutManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.databinding.DialogPhotoPickerBinding;
import ca.translucide.veggiegrow.imagesearch.ImageResult;
import ca.translucide.veggiegrow.imagesearch.ImageSearchClient;
import ca.translucide.veggiegrow.util.ThumbnailLoader;

/**
 * Shows internet image suggestions for a variety name in a grid. On selection, returns the chosen
 * image URL to the host fragment via the Fragment Result API.
 */
public class PhotoPickerDialog extends DialogFragment {

    public static final String RESULT_KEY = "photo_pick_result";
    public static final String RESULT_URL = "full_url";

    private static final String ARG_QUERY = "query";

    private DialogPhotoPickerBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ThumbnailLoader thumbnailLoader;
    private PhotoGridAdapter adapter;

    public static PhotoPickerDialog newInstance(String query) {
        PhotoPickerDialog d = new PhotoPickerDialog();
        Bundle b = new Bundle();
        b.putString(ARG_QUERY, query);
        d.setArguments(b);
        return d;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogPhotoPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        String query = getArguments() != null ? getArguments().getString(ARG_QUERY, "") : "";
        binding.title.setText(getString(R.string.photos_for, query));

        thumbnailLoader = new ThumbnailLoader();
        adapter = new PhotoGridAdapter(thumbnailLoader, this::deliver);
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.recycler.setAdapter(adapter);

        search(query);
    }

    private void search(String query) {
        showProgress();
        executor.execute(() -> {
            List<ImageResult> results;
            boolean failed = false;
            try {
                results = new ImageSearchClient().search(query);
            } catch (Exception e) {
                results = null;
                failed = true;
            }
            final List<ImageResult> finalResults = results;
            final boolean finalFailed = failed;
            postToUi(() -> {
                if (finalFailed || finalResults == null) {
                    showStatus(getString(R.string.photo_search_failed));
                } else if (finalResults.isEmpty()) {
                    showStatus(getString(R.string.no_photos_found));
                } else {
                    adapter.submit(finalResults);
                    showGrid();
                }
            });
        });
    }

    private void deliver(ImageResult result) {
        Bundle out = new Bundle();
        out.putString(RESULT_URL, result.fullUrl);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, out);
        dismiss();
    }

    private void showProgress() {
        if (binding == null) return;
        binding.progress.setVisibility(View.VISIBLE);
        binding.status.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.GONE);
    }

    private void showGrid() {
        if (binding == null) return;
        binding.progress.setVisibility(View.GONE);
        binding.status.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.VISIBLE);
    }

    private void showStatus(String message) {
        if (binding == null) return;
        binding.progress.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.GONE);
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(message);
    }

    private void postToUi(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(() -> {
            if (binding != null) r.run();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            Window window = getDialog().getWindow();
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.9);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (thumbnailLoader != null) thumbnailLoader.shutdown();
        executor.shutdownNow();
        binding = null;
    }
}
