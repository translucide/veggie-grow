package ca.translucide.veggiegrow.data;

import androidx.annotation.Nullable;

import java.io.IOException;

/** Supplies the bearer token sent on each API request. Implementations may block (call off-thread). */
interface TokenProvider {

    /** The current bearer token, or null if no user is signed in. */
    @Nullable
    String token() throws IOException;
}
