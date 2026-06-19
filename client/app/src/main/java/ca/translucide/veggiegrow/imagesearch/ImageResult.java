package ca.translucide.veggiegrow.imagesearch;

/**
 * One image suggestion returned by an {@link ImageSource}.
 */
public class ImageResult {

    /** URL of a small thumbnail shown in the picker grid. */
    public final String thumbUrl;

    /** URL of the full-size image downloaded when the user selects this result. */
    public final String fullUrl;

    public final String title;

    /** Human-readable credit/licence string (CC sources). May be null. */
    public final String attribution;

    /** Name of the source that produced this result (Openverse / Wikimedia / iNaturalist). */
    public final String sourceName;

    public ImageResult(String thumbUrl, String fullUrl, String title, String attribution, String sourceName) {
        this.thumbUrl = thumbUrl;
        this.fullUrl = fullUrl;
        this.title = title;
        this.attribution = attribution;
        this.sourceName = sourceName;
    }

    /** Key used for de-duplication across sources. */
    public String dedupeKey() {
        return fullUrl != null ? fullUrl : thumbUrl;
    }
}
