package ca.translucide.veggiegrow.data;

/**
 * The caller's identity + account state, as returned by {@code GET /v1/me} and
 * {@code POST /v1/accounts}. JSON field names match the server's response.
 */
public class AccountInfo {

    public String uid;
    public String email;
    public String accountId;
    public String accountName;
    public String role;

    /** True when the user is authenticated but not yet a member of any account. */
    public boolean needsOnboarding;

    /** True when the server changed our claims; the client must refresh its ID token before reuse. */
    public boolean tokenStale;

    public boolean hasAccount() {
        return accountId != null && !accountId.isEmpty();
    }

    public boolean isOwner() {
        return "owner".equals(role);
    }
}
