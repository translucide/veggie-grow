package main

import (
	"log"
	"net/http"
	"net/url"
	"strings"

	"firebase.google.com/go/v4/auth"
)

// Server wires the store and Firebase Auth client to the HTTP routes.
type Server struct {
	store         *Store
	auth          *auth.Client
	migrateLegacy bool // one-shot: absorb legacy root data into the first account created
}

// routes builds the request handler. /health is public; everything under /v1 requires a verified
// Firebase ID token (requireAuth). Account scoping and role checks happen per-handler.
func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.handleHealth)

	api := http.NewServeMux()

	// Identity & onboarding (authenticated, but no account required).
	api.HandleFunc("GET /v1/me", s.getMe)
	api.HandleFunc("POST /v1/accounts", s.createAccount)
	api.HandleFunc("GET /v1/account", s.getAccount)

	// Member management (owner-only mutations).
	api.HandleFunc("GET /v1/account/members", s.listMembers)
	api.HandleFunc("POST /v1/account/members", s.inviteMember)
	api.HandleFunc("PUT /v1/account/members/{uid}", s.updateMember)
	api.HandleFunc("DELETE /v1/account/members/{uid}", s.removeMember)
	api.HandleFunc("DELETE /v1/account/invites/{email}", s.cancelInvite)

	// Account-scoped data. Reads need any member; writes need editor+.
	api.HandleFunc("GET /v1/spaces", s.listSpaces)
	api.HandleFunc("POST /v1/spaces", s.createSpace)
	api.HandleFunc("GET /v1/spaces/{code}", s.getSpace)
	api.HandleFunc("PUT /v1/spaces/{code}", s.putSpace)
	api.HandleFunc("DELETE /v1/spaces/{code}", s.deleteSpace)

	api.HandleFunc("GET /v1/spaces/{code}/bins", s.listBins)
	api.HandleFunc("POST /v1/spaces/{code}/bins", s.createBin)
	api.HandleFunc("GET /v1/spaces/{code}/bins/{bin}", s.getBin)
	api.HandleFunc("PUT /v1/spaces/{code}/bins/{bin}", s.putBin)
	api.HandleFunc("DELETE /v1/spaces/{code}/bins/{bin}", s.deleteBin)

	api.HandleFunc("GET /v1/presets", s.listPresets)
	api.HandleFunc("POST /v1/presets", s.createPreset)
	api.HandleFunc("GET /v1/presets/{name}", s.getPreset)
	api.HandleFunc("PUT /v1/presets/{name}", s.putPreset)
	api.HandleFunc("DELETE /v1/presets/{name}", s.deletePreset)

	api.HandleFunc("GET /v1/settings", s.getSettings)
	api.HandleFunc("PUT /v1/settings", s.putSettings)

	mux.Handle("/v1/", s.requireAuth(api))
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// --- guards ------------------------------------------------------------------------------------

// accountScope returns the caller and a store scoped to their account, writing a 401/403 and
// returning ok=false if they're unauthenticated or have no account yet.
func (s *Server) accountScope(w http.ResponseWriter, r *http.Request) (*Principal, *accountStore, bool) {
	p := principalFrom(r)
	if p == nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return nil, nil, false
	}
	if !p.hasAccount() {
		writeError(w, http.StatusForbidden, "no account; create or join one first")
		return nil, nil, false
	}
	return p, s.store.account(p.AccountID), true
}

func requireWrite(w http.ResponseWriter, p *Principal) bool {
	if !p.canWrite() {
		writeError(w, http.StatusForbidden, "your role is read-only (viewer)")
		return false
	}
	return true
}

func requireOwner(w http.ResponseWriter, p *Principal) bool {
	if !p.isOwner() {
		writeError(w, http.StatusForbidden, "requires owner role")
		return false
	}
	return true
}

// --- identity / onboarding -------------------------------------------------------------------

func (s *Server) getMe(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	if p == nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if p.hasAccount() {
		name := ""
		if acc, err := s.store.GetAccount(r.Context(), p.AccountID); err == nil {
			name = acc.Name
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"uid": p.UID, "email": p.Email,
			"accountId": p.AccountID, "accountName": name, "role": p.Role,
		})
		return
	}
	// No account on the token yet — auto-accept a pending invite matching this email.
	if p.Email != "" {
		if inv, err := s.store.FindInvite(r.Context(), p.Email); err == nil && inv != nil {
			m := Member{UID: p.UID, Email: p.Email, Role: inv.Role, AddedAt: s.store.clock()}
			if err := s.store.UpsertMember(r.Context(), inv.AccountID, m); err != nil {
				writeError(w, http.StatusInternalServerError, "could not join account")
				return
			}
			if err := s.setClaims(r.Context(), p.UID, inv.AccountID, inv.Role); err != nil {
				writeError(w, http.StatusInternalServerError, "could not update access")
				return
			}
			_ = s.store.DeleteInvite(r.Context(), p.Email)
			writeJSON(w, http.StatusOK, map[string]any{
				"uid": p.UID, "email": p.Email,
				"accountId": inv.AccountID, "role": inv.Role,
				"tokenStale": true, // client must force-refresh its ID token, then re-call /me
			})
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"uid": p.UID, "email": p.Email, "needsOnboarding": true,
	})
}

func (s *Server) createAccount(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	if p == nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if p.hasAccount() {
		writeError(w, http.StatusConflict, "already in an account")
		return
	}
	var body struct {
		Name string `json:"name"`
	}
	if !decodeBody(w, r, &body) {
		return
	}
	name := strings.TrimSpace(body.Name)
	if name == "" {
		name = "My Library"
	}
	acc, err := s.store.CreateAccount(r.Context(), name, p.UID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	m := Member{UID: p.UID, Email: p.Email, Role: RoleOwner, AddedAt: s.store.clock()}
	if err := s.store.UpsertMember(r.Context(), acc.ID, m); err != nil {
		writeError(w, http.StatusInternalServerError, "could not create membership")
		return
	}
	if err := s.setClaims(r.Context(), p.UID, acc.ID, RoleOwner); err != nil {
		writeError(w, http.StatusInternalServerError, "could not update access")
		return
	}
	// One-shot cutover: move the pre-multi-tenant root library into this first owner's account.
	if s.migrateLegacy {
		if err := s.store.MigrateLegacyInto(r.Context(), acc.ID); err != nil {
			log.Printf("legacy migration into %s failed: %v", acc.ID, err)
		}
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"accountId": acc.ID, "accountName": acc.Name, "role": RoleOwner,
		"tokenStale": true,
	})
}

func (s *Server) getAccount(w http.ResponseWriter, r *http.Request) {
	p, _, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	acc, err := s.store.GetAccount(r.Context(), p.AccountID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, acc)
}

// --- member management -----------------------------------------------------------------------

func (s *Server) listMembers(w http.ResponseWriter, r *http.Request) {
	p, _, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	members, err := s.store.ListMembers(r.Context(), p.AccountID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	invites, err := s.store.ListInvitesForAccount(r.Context(), p.AccountID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": members, "invites": invites})
}

func (s *Server) inviteMember(w http.ResponseWriter, r *http.Request) {
	p, _, ok := s.accountScope(w, r)
	if !ok || !requireOwner(w, p) {
		return
	}
	var body struct {
		Email string `json:"email"`
		Role  string `json:"role"`
	}
	if !decodeBody(w, r, &body) {
		return
	}
	email := strings.TrimSpace(strings.ToLower(body.Email))
	if email == "" {
		writeError(w, http.StatusBadRequest, "email is required")
		return
	}
	if body.Role != RoleEditor && body.Role != RoleViewer {
		writeError(w, http.StatusBadRequest, "role must be editor or viewer")
		return
	}
	if err := s.store.CreateInvite(r.Context(), p.AccountID, email, body.Role); err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"email": email, "role": body.Role, "pending": true})
}

func (s *Server) updateMember(w http.ResponseWriter, r *http.Request) {
	p, _, ok := s.accountScope(w, r)
	if !ok || !requireOwner(w, p) {
		return
	}
	uid := r.PathValue("uid")
	var body struct {
		Role string `json:"role"`
	}
	if !decodeBody(w, r, &body) {
		return
	}
	if body.Role != RoleEditor && body.Role != RoleViewer {
		writeError(w, http.StatusBadRequest, "role must be editor or viewer")
		return
	}
	if acc, err := s.store.GetAccount(r.Context(), p.AccountID); err == nil && acc.OwnerUID == uid {
		writeError(w, http.StatusBadRequest, "cannot change the account owner's role")
		return
	}
	m, err := s.store.GetMember(r.Context(), p.AccountID, uid)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	m.Role = body.Role
	if err := s.store.UpsertMember(r.Context(), p.AccountID, *m); err != nil {
		writeStoreError(w, err)
		return
	}
	if err := s.setClaims(r.Context(), uid, p.AccountID, body.Role); err != nil {
		writeError(w, http.StatusInternalServerError, "could not update access")
		return
	}
	writeJSON(w, http.StatusOK, m)
}

func (s *Server) removeMember(w http.ResponseWriter, r *http.Request) {
	p, _, ok := s.accountScope(w, r)
	if !ok || !requireOwner(w, p) {
		return
	}
	uid := r.PathValue("uid")
	if acc, err := s.store.GetAccount(r.Context(), p.AccountID); err == nil && acc.OwnerUID == uid {
		writeError(w, http.StatusBadRequest, "cannot remove the account owner")
		return
	}
	if err := s.store.DeleteMember(r.Context(), p.AccountID, uid); err != nil {
		writeStoreError(w, err)
		return
	}
	// Revoke their access by clearing their claims.
	if err := s.setClaims(r.Context(), uid, "", ""); err != nil {
		writeError(w, http.StatusInternalServerError, "removed, but could not revoke access")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) cancelInvite(w http.ResponseWriter, r *http.Request) {
	p, _, ok := s.accountScope(w, r)
	if !ok || !requireOwner(w, p) {
		return
	}
	email := r.PathValue("email")
	inv, err := s.store.FindInvite(r.Context(), email)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if inv.AccountID != p.AccountID {
		writeError(w, http.StatusNotFound, "invite not found")
		return
	}
	if err := s.store.DeleteInvite(r.Context(), email); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- spaces ----------------------------------------------------------------------------------

func (s *Server) listSpaces(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	spaces, err := as.ListSpaces(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"spaces": spaces})
}

func (s *Server) createSpace(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	var sp Space
	if !decodeBody(w, r, &sp) {
		return
	}
	if strings.TrimSpace(sp.Code) == "" {
		writeError(w, http.StatusBadRequest, "code is required")
		return
	}
	out, err := as.CreateSpace(r.Context(), &sp)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	w.Header().Set("Location", "/v1/spaces/"+url.PathEscape(strings.ToLower(out.Code)))
	writeJSON(w, http.StatusCreated, out)
}

func (s *Server) getSpace(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	out, err := as.GetSpace(r.Context(), r.PathValue("code"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putSpace(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var sp Space
	if !decodeBody(w, r, &sp) {
		return
	}
	out, err := as.PutSpace(r.Context(), r.PathValue("code"), &sp, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deleteSpace(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	if err := as.DeleteSpace(r.Context(), r.PathValue("code")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- bins ------------------------------------------------------------------------------------

func (s *Server) listBins(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	bins, err := as.ListBins(r.Context(), r.PathValue("code"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"bins": bins})
}

func (s *Server) createBin(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	var b Bin
	if !decodeBody(w, r, &b) {
		return
	}
	if strings.TrimSpace(b.Code) == "" {
		writeError(w, http.StatusBadRequest, "code is required")
		return
	}
	space := r.PathValue("code")
	out, err := as.CreateBin(r.Context(), space, &b)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	w.Header().Set("Location", "/v1/spaces/"+url.PathEscape(strings.ToLower(space))+"/bins/"+url.PathEscape(strings.ToLower(out.Code)))
	writeJSON(w, http.StatusCreated, out)
}

func (s *Server) getBin(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	out, err := as.GetBin(r.Context(), r.PathValue("code"), r.PathValue("bin"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putBin(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var b Bin
	if !decodeBody(w, r, &b) {
		return
	}
	out, err := as.PutBin(r.Context(), r.PathValue("code"), r.PathValue("bin"), &b, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deleteBin(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	if err := as.DeleteBin(r.Context(), r.PathValue("code"), r.PathValue("bin")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- presets ---------------------------------------------------------------------------------

func (s *Server) listPresets(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	presets, err := as.ListPresets(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"presets": presets})
}

func (s *Server) createPreset(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	var pr Preset
	if !decodeBody(w, r, &pr) {
		return
	}
	if strings.TrimSpace(pr.Name) == "" {
		writeError(w, http.StatusBadRequest, "name is required")
		return
	}
	out, err := as.CreatePreset(r.Context(), &pr)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	w.Header().Set("Location", "/v1/presets/"+url.PathEscape(strings.ToLower(out.Name)))
	writeJSON(w, http.StatusCreated, out)
}

func (s *Server) getPreset(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	out, err := as.GetPreset(r.Context(), r.PathValue("name"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putPreset(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var pr Preset
	if !decodeBody(w, r, &pr) {
		return
	}
	out, err := as.PutPreset(r.Context(), r.PathValue("name"), &pr, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deletePreset(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	if err := as.DeletePreset(r.Context(), r.PathValue("name")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- settings --------------------------------------------------------------------------------

func (s *Server) getSettings(w http.ResponseWriter, r *http.Request) {
	_, as, ok := s.accountScope(w, r)
	if !ok {
		return
	}
	out, err := as.GetSettings(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putSettings(w http.ResponseWriter, r *http.Request) {
	p, as, ok := s.accountScope(w, r)
	if !ok || !requireWrite(w, p) {
		return
	}
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var st Settings
	if !decodeBody(w, r, &st) {
		return
	}
	out, err := as.PutSettings(r.Context(), &st, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}
