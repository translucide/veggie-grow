package ca.translucide.veggiegrow.imagesearch;

import java.util.List;

/**
 * A provider of image suggestions for a query. Implementations perform one network request and
 * parse the response. They run off the main thread (see {@link ImageSearchClient}).
 */
public interface ImageSource {

    String name();

    List<ImageResult> search(String query) throws Exception;
}
