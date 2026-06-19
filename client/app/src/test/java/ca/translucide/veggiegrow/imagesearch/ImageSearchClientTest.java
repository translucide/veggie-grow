package ca.translucide.veggiegrow.imagesearch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImageSearchClientTest {

    /** Stub source returning a fixed list (or throwing) — no network. */
    private static class StubSource implements ImageSource {
        private final String name;
        private final List<ImageResult> results;
        private final boolean fail;

        StubSource(String name, List<ImageResult> results, boolean fail) {
            this.name = name;
            this.results = results;
            this.fail = fail;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<ImageResult> search(String query) throws Exception {
            if (fail) throw new RuntimeException("boom");
            return results;
        }
    }

    private ImageResult img(String url, String src) {
        return new ImageResult(url, url, "t", null, src);
    }

    @Test
    public void mergesInSourcePriorityOrder() {
        ImageSource a = new StubSource("A", Arrays.asList(img("a1", "A"), img("a2", "A")), false);
        ImageSource b = new StubSource("B", Arrays.asList(img("b1", "B")), false);

        List<ImageResult> out = new ImageSearchClient(Arrays.asList(a, b)).search("x");

        assertEquals(3, out.size());
        assertEquals("a1", out.get(0).fullUrl);
        assertEquals("a2", out.get(1).fullUrl);
        assertEquals("b1", out.get(2).fullUrl);
    }

    @Test
    public void dedupesByUrlAcrossSources() {
        ImageSource a = new StubSource("A", Arrays.asList(img("same", "A")), false);
        ImageSource b = new StubSource("B", Arrays.asList(img("same", "B"), img("other", "B")), false);

        List<ImageResult> out = new ImageSearchClient(Arrays.asList(a, b)).search("x");

        assertEquals(2, out.size());
        assertEquals("same", out.get(0).fullUrl);
        assertEquals("A", out.get(0).sourceName); // first one wins
        assertEquals("other", out.get(1).fullUrl);
    }

    @Test
    public void failingSourceIsSkipped() {
        ImageSource a = new StubSource("A", null, true);
        ImageSource b = new StubSource("B", Arrays.asList(img("b1", "B")), false);

        List<ImageResult> out = new ImageSearchClient(Arrays.asList(a, b)).search("x");

        assertEquals(1, out.size());
        assertEquals("b1", out.get(0).fullUrl);
    }

    @Test
    public void capsAtMaxResults() {
        List<ImageResult> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(img("u" + i, "A"));
        ImageSource a = new StubSource("A", many, false);

        List<ImageResult> out = new ImageSearchClient(Arrays.asList(a)).search("x");

        assertEquals(ImageSearchClient.MAX_RESULTS, out.size());
    }

    @Test
    public void blankQueryReturnsEmpty() {
        ImageSource a = new StubSource("A", Arrays.asList(img("a1", "A")), false);
        assertTrue(new ImageSearchClient(Arrays.asList(a)).search("  ").isEmpty());
    }
}
