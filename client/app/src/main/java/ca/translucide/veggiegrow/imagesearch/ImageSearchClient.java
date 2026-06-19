package ca.translucide.veggiegrow.imagesearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates image suggestions across several {@link ImageSource}s. Sources run concurrently; the
 * merged list is ordered by source priority (the order sources are supplied), de-duplicated by URL,
 * and capped at {@link #MAX_RESULTS}.
 *
 * <p>Android-free so it can be unit tested with stub sources.
 */
public class ImageSearchClient {

    public static final int MAX_RESULTS = 20;
    private static final int PER_SOURCE_TIMEOUT_SECONDS = 15;

    private final List<ImageSource> sources;

    /** Default client using the three no-API-key sources. */
    public ImageSearchClient() {
        this(Arrays.asList(new OpenverseSource(), new WikimediaSource(), new INaturalistSource()));
    }

    public ImageSearchClient(List<ImageSource> sources) {
        this.sources = sources;
    }

    /**
     * Searches all sources and returns the merged, de-duplicated, capped result list. Never throws
     * for individual source failures — a failing source simply contributes nothing.
     */
    public List<ImageResult> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        final String q = query.trim();

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, sources.size()));
        try {
            List<Future<List<ImageResult>>> futures = new ArrayList<>();
            for (final ImageSource source : sources) {
                Callable<List<ImageResult>> task = () -> {
                    try {
                        List<ImageResult> r = source.search(q);
                        return r != null ? r : new ArrayList<>();
                    } catch (Exception e) {
                        return new ArrayList<>();
                    }
                };
                futures.add(executor.submit(task));
            }

            // Merge in source (priority) order, regardless of completion order.
            Set<String> seen = new LinkedHashSet<>();
            List<ImageResult> merged = new ArrayList<>();
            for (Future<List<ImageResult>> f : futures) {
                List<ImageResult> partial = await(f);
                for (ImageResult r : partial) {
                    if (merged.size() >= MAX_RESULTS) break;
                    String key = r.dedupeKey();
                    if (key != null && seen.add(key)) {
                        merged.add(r);
                    }
                }
                if (merged.size() >= MAX_RESULTS) break;
            }
            return merged;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ImageResult> await(Future<List<ImageResult>> f) {
        try {
            List<ImageResult> r = f.get(PER_SOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return r != null ? r : new ArrayList<>();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            return new ArrayList<>();
        }
    }
}
