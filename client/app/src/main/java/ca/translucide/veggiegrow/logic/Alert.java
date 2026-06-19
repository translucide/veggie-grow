package ca.translucide.veggiegrow.logic;

/**
 * An immutable alert produced by {@link AlertEngine}.
 */
public class Alert {

    public enum Type {
        HARVEST,
        RESERVOIR
    }

    public final Type type;
    public final String title;
    public final String message;
    /** Reference code: a bin reference ("A1") for harvest, a space code ("A") for reservoir. */
    public final String reference;

    public Alert(Type type, String title, String message, String reference) {
        this.type = type;
        this.title = title;
        this.message = message;
        this.reference = reference;
    }
}
