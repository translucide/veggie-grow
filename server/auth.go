package main

import (
	"context"
	"net/http"
	"strings"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
)

// Roles, ranked so authorization can ask "at least editor?". owner > editor > viewer.
const (
	RoleOwner  = "owner"
	RoleEditor = "editor"
	RoleViewer = "viewer"
)

func roleRank(role string) int {
	switch role {
	case RoleOwner:
		return 3
	case RoleEditor:
		return 2
	case RoleViewer:
		return 1
	default:
		return 0
	}
}

// Principal is the authenticated caller, derived from a verified Firebase ID token plus the custom
// claims (accountId, role) we stamp on the user. A caller with no account yet has an empty
// AccountID and must create or join one (handled by /v1/me and /v1/accounts).
type Principal struct {
	UID       string
	Email     string
	AccountID string
	Role      string
}

func (p *Principal) hasAccount() bool { return p != nil && p.AccountID != "" }
func (p *Principal) canWrite() bool   { return p != nil && roleRank(p.Role) >= roleRank(RoleEditor) }
func (p *Principal) isOwner() bool    { return p != nil && p.Role == RoleOwner }

type ctxKey int

const principalCtxKey ctxKey = iota

func principalFrom(r *http.Request) *Principal {
	p, _ := r.Context().Value(principalCtxKey).(*Principal)
	return p
}

// newAuthClient builds the Firebase Auth client used to verify ID tokens and manage custom claims.
// On Cloud Run it authenticates via the service account's Application Default Credentials.
func newAuthClient(ctx context.Context, projectID string) (*auth.Client, error) {
	app, err := firebase.NewApp(ctx, &firebase.Config{ProjectID: projectID})
	if err != nil {
		return nil, err
	}
	return app.Auth(ctx)
}

// requireAuth verifies the Firebase ID token in the Authorization header and attaches the resulting
// Principal to the request context. Everything under /v1 is wrapped with this.
func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		idToken := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if idToken == "" {
			writeError(w, http.StatusUnauthorized, "missing bearer token")
			return
		}
		tok, err := s.auth.VerifyIDToken(r.Context(), idToken)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "invalid or expired token")
			return
		}
		p := &Principal{UID: tok.UID}
		if v, ok := tok.Claims["email"].(string); ok {
			p.Email = v
		}
		if v, ok := tok.Claims["accountId"].(string); ok {
			p.AccountID = v
		}
		if v, ok := tok.Claims["role"].(string); ok {
			p.Role = v
		}
		ctx := context.WithValue(r.Context(), principalCtxKey, p)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// setClaims stamps (or clears, with empty strings) the account/role custom claims on a user. The
// affected client must force-refresh its ID token to see the change.
func (s *Server) setClaims(ctx context.Context, uid, accountID, role string) error {
	return s.auth.SetCustomUserClaims(ctx, uid, map[string]interface{}{
		"accountId": accountID,
		"role":      role,
	})
}
