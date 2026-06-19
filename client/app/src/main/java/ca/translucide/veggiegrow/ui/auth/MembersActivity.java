package ca.translucide.veggiegrow.ui.auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.AccountManager;
import ca.translucide.veggiegrow.data.MembersList;
import ca.translucide.veggiegrow.databinding.ActivityMembersBinding;

/**
 * Owner-only screen to view and manage who can access the shared library: lists members and pending
 * invites, invites new people (by email + role), changes a member's role, and removes members.
 * All mutations are owner-gated server-side; a non-owner who reaches here simply gets 403s.
 */
public class MembersActivity extends AppCompatActivity {

    private ActivityMembersBinding binding;
    private AccountManager account;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMembersBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        account = new AccountManager(this);
        binding.btnInvite.setOnClickListener(v -> showInviteDialog());
        load();
    }

    private void load() {
        binding.progress.setVisibility(View.VISIBLE);
        account.listMembers((ml, error) -> {
            if (binding == null) return;
            binding.progress.setVisibility(View.GONE);
            if (error != null || ml == null) {
                Toast.makeText(this, R.string.members_error, Toast.LENGTH_LONG).show();
                return;
            }
            render(ml);
        });
    }

    private void render(@NonNull MembersList ml) {
        binding.membersContainer.removeAllViews();
        for (MembersList.Member m : ml.members) {
            binding.membersContainer.addView(memberRow(m));
        }

        boolean hasInvites = ml.invites != null && !ml.invites.isEmpty();
        binding.invitesHeader.setVisibility(hasInvites ? View.VISIBLE : View.GONE);
        binding.invitesContainer.removeAllViews();
        if (hasInvites) {
            for (MembersList.Invite inv : ml.invites) {
                binding.invitesContainer.addView(inviteRow(inv));
            }
        }
    }

    private View memberRow(@NonNull MembersList.Member m) {
        View row = inflate(R.layout.item_member, binding.membersContainer);
        ((android.widget.TextView) row.findViewById(R.id.text_member_email)).setText(m.email);
        ((android.widget.TextView) row.findViewById(R.id.text_member_role)).setText(roleLabel(m.role));
        View manage = row.findViewById(R.id.btn_member_manage);
        if ("owner".equals(m.role)) {
            manage.setVisibility(View.GONE); // the owner can't be changed or removed
        } else {
            manage.setOnClickListener(v -> showManageDialog(m));
        }
        return row;
    }

    private View inviteRow(@NonNull MembersList.Invite inv) {
        View row = inflate(R.layout.item_invite, binding.invitesContainer);
        ((android.widget.TextView) row.findViewById(R.id.text_invite_email)).setText(inv.email);
        ((android.widget.TextView) row.findViewById(R.id.text_invite_role))
                .setText(getString(R.string.invited_as, roleLabel(inv.role)));
        row.findViewById(R.id.btn_invite_cancel).setOnClickListener(v ->
                act(cb -> account.cancelInvite(inv.email, cb)));
        return row;
    }

    private void showManageDialog(@NonNull MembersList.Member m) {
        boolean isEditor = "editor".equals(m.role);
        String flipLabel = getString(isEditor ? R.string.change_to_viewer : R.string.change_to_editor);
        String flipRole = isEditor ? "viewer" : "editor";
        CharSequence[] options = {flipLabel, getString(R.string.remove_member)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(m.email)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        act(cb -> account.updateMemberRole(m.uid, flipRole, cb));
                    } else {
                        act(cb -> account.removeMember(m.uid, cb));
                    }
                })
                .show();
    }

    private void showInviteDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_invite, null, false);
        TextInputEditText emailInput = content.findViewById(R.id.input_invite_email);
        MaterialAutoCompleteTextView roleInput = content.findViewById(R.id.input_invite_role);
        String[] roleLabels = {getString(R.string.role_editor), getString(R.string.role_viewer)};
        roleInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, roleLabels));
        roleInput.setText(roleLabels[1], false); // default: viewer

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.invite_someone)
                .setView(content)
                .setPositiveButton(R.string.invite, (d, w) -> {
                    String email = emailInput.getText() == null ? "" : emailInput.getText().toString().trim();
                    if (email.isEmpty()) {
                        Toast.makeText(this, R.string.invite_email_hint, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String role = roleLabels[0].contentEquals(roleInput.getText()) ? "editor" : "viewer";
                    act(cb -> account.inviteMember(email, role, cb));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Runs an account mutation, then reloads the list (or toasts on error). */
    private void act(@NonNull java.util.function.Consumer<AccountManager.Callback<Void>> action) {
        action.accept((value, error) -> {
            if (binding == null) return;
            if (error != null) {
                Toast.makeText(this, R.string.action_error, Toast.LENGTH_LONG).show();
                return;
            }
            load();
        });
    }

    private String roleLabel(@Nullable String role) {
        if ("owner".equals(role)) return getString(R.string.role_owner);
        if ("editor".equals(role)) return getString(R.string.role_editor);
        return getString(R.string.role_viewer);
    }

    private View inflate(int layout, ViewGroup parent) {
        return LayoutInflater.from(this).inflate(layout, parent, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
