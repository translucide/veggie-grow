package ca.translucide.veggiegrow.data;

import java.util.ArrayList;
import java.util.List;

/** Response of {@code GET /v1/account/members}: current members plus pending (not-yet-joined) invites. */
public class MembersList {

    public List<Member> members = new ArrayList<>();
    public List<Invite> invites = new ArrayList<>();

    public static class Member {
        public String uid;
        public String email;
        public String role;
    }

    public static class Invite {
        public String email;
        public String role;
    }
}
