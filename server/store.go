package main

import (
	"context"
	"errors"
	"strings"

	"cloud.google.com/go/firestore"
	"google.golang.org/api/iterator"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// Sentinel errors the handlers map to HTTP status codes.
var (
	ErrNotFound     = errors.New("not found")
	ErrConflict     = errors.New("already exists")
	ErrPrecondition = errors.New("revision mismatch")
	ErrInvalidKey   = errors.New("invalid key")
)

// revisioned is implemented by every top-level resource so the generic write helpers can stamp
// the rev/updatedAt metadata uniformly.
type revisioned interface {
	GetRev() int64
	SetRev(int64)
	SetUpdatedAt(int64)
}

// Store is the Firestore-backed persistence layer. clock returns the current time in epoch millis
// and is injectable so tests can use a fixed value.
type Store struct {
	fs    *firestore.Client
	clock func() int64
}

func NewStore(fs *firestore.Client, clock func() int64) *Store {
	return &Store{fs: fs, clock: clock}
}

// account returns a view of the store scoped to one tenant's library.
func (s *Store) account(accountID string) *accountStore {
	return &accountStore{s: s, accountID: accountID}
}

// canonical normalises a natural key (space/bin code, preset name, email) into a Firestore document
// ID. Keys are case-insensitive; we reject the handful of values Firestore forbids as document IDs.
func canonical(key string) (string, error) {
	k := strings.ToLower(strings.TrimSpace(key))
	if k == "" || k == "." || k == ".." || strings.Contains(k, "/") {
		return "", ErrInvalidKey
	}
	if strings.HasPrefix(k, "__") && strings.HasSuffix(k, "__") {
		return "", ErrInvalidKey
	}
	return k, nil
}

// --- generic document helpers ------------------------------------------------------------------

func getDoc[T any](ctx context.Context, coll *firestore.CollectionRef, id string) (*T, error) {
	snap, err := coll.Doc(id).Get(ctx)
	if status.Code(err) == codes.NotFound {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	var v T
	if err := snap.DataTo(&v); err != nil {
		return nil, err
	}
	return &v, nil
}

// createDoc writes a new document, failing with ErrConflict if one already exists at id.
func createDoc[T any, PT interface {
	*T
	revisioned
}](ctx context.Context, coll *firestore.CollectionRef, id string, in PT, now int64) error {
	in.SetRev(1)
	in.SetUpdatedAt(now)
	_, err := coll.Doc(id).Create(ctx, in)
	if status.Code(err) == codes.AlreadyExists {
		return ErrConflict
	}
	return err
}

// putDoc upserts a document inside a transaction, honouring an optional optimistic-concurrency
// guard: when ifMatch is non-nil and does not equal the current rev, it returns ErrPrecondition.
func putDoc[T any, PT interface {
	*T
	revisioned
}](ctx context.Context, fs *firestore.Client, ref *firestore.DocumentRef, in PT, ifMatch *int64, now int64) error {
	return fs.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		var cur int64
		snap, err := tx.Get(ref)
		switch {
		case status.Code(err) == codes.NotFound:
			cur = 0
		case err != nil:
			return err
		default:
			var existing T
			if err := snap.DataTo(&existing); err != nil {
				return err
			}
			cur = PT(&existing).GetRev()
		}
		if ifMatch != nil && *ifMatch != cur {
			return ErrPrecondition
		}
		in.SetRev(cur + 1)
		in.SetUpdatedAt(now)
		return tx.Set(ref, in)
	})
}

// ===============================================================================================
// Account-scoped data (spaces / bins / presets / settings live under accounts/{id})
// ===============================================================================================

type accountStore struct {
	s         *Store
	accountID string
}

func (a *accountStore) root() *firestore.DocumentRef {
	return a.s.fs.Collection("accounts").Doc(a.accountID)
}
func (a *accountStore) spacesCol() *firestore.CollectionRef  { return a.root().Collection("spaces") }
func (a *accountStore) presetsCol() *firestore.CollectionRef { return a.root().Collection("presets") }
func (a *accountStore) binsCol(spaceID string) *firestore.CollectionRef {
	return a.spacesCol().Doc(spaceID).Collection("bins")
}
func (a *accountStore) configDoc() *firestore.DocumentRef {
	return a.root().Collection("config").Doc("settings")
}

// --- spaces ------------------------------------------------------------------------------------

func (a *accountStore) ListSpaces(ctx context.Context) ([]Space, error) {
	out := []Space{}
	it := a.spacesCol().Documents(ctx)
	defer it.Stop()
	for {
		snap, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return nil, err
		}
		var sp Space
		if err := snap.DataTo(&sp); err != nil {
			return nil, err
		}
		bins, err := a.listBinsByID(ctx, snap.Ref.ID)
		if err != nil {
			return nil, err
		}
		sp.Bins = bins
		out = append(out, sp)
	}
	return out, nil
}

func (a *accountStore) GetSpace(ctx context.Context, code string) (*Space, error) {
	id, err := canonical(code)
	if err != nil {
		return nil, err
	}
	sp, err := getDoc[Space](ctx, a.spacesCol(), id)
	if err != nil {
		return nil, err
	}
	bins, err := a.listBinsByID(ctx, id)
	if err != nil {
		return nil, err
	}
	sp.Bins = bins
	return sp, nil
}

func (a *accountStore) CreateSpace(ctx context.Context, sp *Space) (*Space, error) {
	id, err := canonical(sp.Code)
	if err != nil {
		return nil, err
	}
	if err := createDoc(ctx, a.spacesCol(), id, sp, a.s.clock()); err != nil {
		return nil, err
	}
	sp.Bins = []Bin{}
	return sp, nil
}

func (a *accountStore) PutSpace(ctx context.Context, code string, sp *Space, ifMatch *int64) (*Space, error) {
	id, err := canonical(code)
	if err != nil {
		return nil, err
	}
	sp.Code = code // the path is authoritative for identity
	if err := putDoc(ctx, a.s.fs, a.spacesCol().Doc(id), sp, ifMatch, a.s.clock()); err != nil {
		return nil, err
	}
	bins, err := a.listBinsByID(ctx, id)
	if err != nil {
		return nil, err
	}
	sp.Bins = bins
	return sp, nil
}

func (a *accountStore) DeleteSpace(ctx context.Context, code string) error {
	id, err := canonical(code)
	if err != nil {
		return err
	}
	if err := a.requireSpace(ctx, id); err != nil {
		return err
	}
	// Delete the bins subcollection first (Firestore does not cascade).
	it := a.binsCol(id).Documents(ctx)
	defer it.Stop()
	for {
		snap, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return err
		}
		if _, err := snap.Ref.Delete(ctx); err != nil {
			return err
		}
	}
	_, err = a.spacesCol().Doc(id).Delete(ctx)
	return err
}

func (a *accountStore) requireSpace(ctx context.Context, spaceID string) error {
	_, err := a.spacesCol().Doc(spaceID).Get(ctx)
	if status.Code(err) == codes.NotFound {
		return ErrNotFound
	}
	return err
}

// --- bins --------------------------------------------------------------------------------------

func (a *accountStore) listBinsByID(ctx context.Context, spaceID string) ([]Bin, error) {
	out := []Bin{}
	it := a.binsCol(spaceID).Documents(ctx)
	defer it.Stop()
	for {
		snap, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return nil, err
		}
		var b Bin
		if err := snap.DataTo(&b); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, nil
}

func (a *accountStore) ListBins(ctx context.Context, spaceCode string) ([]Bin, error) {
	sid, err := canonical(spaceCode)
	if err != nil {
		return nil, err
	}
	if err := a.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	return a.listBinsByID(ctx, sid)
}

func (a *accountStore) GetBin(ctx context.Context, spaceCode, binCode string) (*Bin, error) {
	sid, bid, err := binIDs(spaceCode, binCode)
	if err != nil {
		return nil, err
	}
	if err := a.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	return getDoc[Bin](ctx, a.binsCol(sid), bid)
}

func (a *accountStore) CreateBin(ctx context.Context, spaceCode string, b *Bin) (*Bin, error) {
	sid, bid, err := binIDs(spaceCode, b.Code)
	if err != nil {
		return nil, err
	}
	if err := a.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	if err := createDoc(ctx, a.binsCol(sid), bid, b, a.s.clock()); err != nil {
		return nil, err
	}
	return b, nil
}

func (a *accountStore) PutBin(ctx context.Context, spaceCode, binCode string, b *Bin, ifMatch *int64) (*Bin, error) {
	sid, bid, err := binIDs(spaceCode, binCode)
	if err != nil {
		return nil, err
	}
	if err := a.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	b.Code = binCode
	if err := putDoc(ctx, a.s.fs, a.binsCol(sid).Doc(bid), b, ifMatch, a.s.clock()); err != nil {
		return nil, err
	}
	return b, nil
}

func (a *accountStore) DeleteBin(ctx context.Context, spaceCode, binCode string) error {
	sid, bid, err := binIDs(spaceCode, binCode)
	if err != nil {
		return err
	}
	if err := a.requireSpace(ctx, sid); err != nil {
		return err
	}
	ref := a.binsCol(sid).Doc(bid)
	if _, err := ref.Get(ctx); status.Code(err) == codes.NotFound {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	_, err = ref.Delete(ctx)
	return err
}

func binIDs(spaceCode, binCode string) (string, string, error) {
	sid, err := canonical(spaceCode)
	if err != nil {
		return "", "", err
	}
	bid, err := canonical(binCode)
	if err != nil {
		return "", "", err
	}
	return sid, bid, nil
}

// --- presets -----------------------------------------------------------------------------------

func (a *accountStore) ListPresets(ctx context.Context) ([]Preset, error) {
	out := []Preset{}
	it := a.presetsCol().Documents(ctx)
	defer it.Stop()
	for {
		snap, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return nil, err
		}
		var p Preset
		if err := snap.DataTo(&p); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, nil
}

func (a *accountStore) GetPreset(ctx context.Context, name string) (*Preset, error) {
	id, err := canonical(name)
	if err != nil {
		return nil, err
	}
	return getDoc[Preset](ctx, a.presetsCol(), id)
}

func (a *accountStore) CreatePreset(ctx context.Context, p *Preset) (*Preset, error) {
	id, err := canonical(p.Name)
	if err != nil {
		return nil, err
	}
	if err := createDoc(ctx, a.presetsCol(), id, p, a.s.clock()); err != nil {
		return nil, err
	}
	return p, nil
}

func (a *accountStore) PutPreset(ctx context.Context, name string, p *Preset, ifMatch *int64) (*Preset, error) {
	id, err := canonical(name)
	if err != nil {
		return nil, err
	}
	p.Name = name
	if err := putDoc(ctx, a.s.fs, a.presetsCol().Doc(id), p, ifMatch, a.s.clock()); err != nil {
		return nil, err
	}
	return p, nil
}

func (a *accountStore) DeletePreset(ctx context.Context, name string) error {
	id, err := canonical(name)
	if err != nil {
		return err
	}
	ref := a.presetsCol().Doc(id)
	if _, err := ref.Get(ctx); status.Code(err) == codes.NotFound {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	_, err = ref.Delete(ctx)
	return err
}

// --- settings singleton ------------------------------------------------------------------------

func (a *accountStore) GetSettings(ctx context.Context) (*Settings, error) {
	snap, err := a.configDoc().Get(ctx)
	if status.Code(err) == codes.NotFound {
		d := DefaultSettings()
		return &d, nil
	}
	if err != nil {
		return nil, err
	}
	var st Settings
	if err := snap.DataTo(&st); err != nil {
		return nil, err
	}
	return &st, nil
}

func (a *accountStore) PutSettings(ctx context.Context, st *Settings, ifMatch *int64) (*Settings, error) {
	if err := putDoc(ctx, a.s.fs, a.configDoc(), st, ifMatch, a.s.clock()); err != nil {
		return nil, err
	}
	return st, nil
}

// ===============================================================================================
// Accounts, members, invites
// ===============================================================================================

func (s *Store) accountsCol() *firestore.CollectionRef { return s.fs.Collection("accounts") }
func (s *Store) invitesCol() *firestore.CollectionRef  { return s.fs.Collection("invites") }
func (s *Store) membersCol(accountID string) *firestore.CollectionRef {
	return s.accountsCol().Doc(accountID).Collection("members")
}

// CreateAccount creates a new account with a server-assigned ID and returns it populated.
func (s *Store) CreateAccount(ctx context.Context, name, ownerUID string) (*Account, error) {
	ref := s.accountsCol().NewDoc()
	acc := Account{Name: strings.TrimSpace(name), OwnerUID: ownerUID, CreatedAt: s.clock()}
	if _, err := ref.Set(ctx, acc); err != nil {
		return nil, err
	}
	acc.ID = ref.ID
	return &acc, nil
}

func (s *Store) GetAccount(ctx context.Context, accountID string) (*Account, error) {
	snap, err := s.accountsCol().Doc(accountID).Get(ctx)
	if status.Code(err) == codes.NotFound {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	var acc Account
	if err := snap.DataTo(&acc); err != nil {
		return nil, err
	}
	acc.ID = snap.Ref.ID
	return &acc, nil
}

func (s *Store) UpsertMember(ctx context.Context, accountID string, m Member) error {
	_, err := s.membersCol(accountID).Doc(m.UID).Set(ctx, m)
	return err
}

func (s *Store) GetMember(ctx context.Context, accountID, uid string) (*Member, error) {
	snap, err := s.membersCol(accountID).Doc(uid).Get(ctx)
	if status.Code(err) == codes.NotFound {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	var m Member
	if err := snap.DataTo(&m); err != nil {
		return nil, err
	}
	return &m, nil
}

func (s *Store) ListMembers(ctx context.Context, accountID string) ([]Member, error) {
	out := []Member{}
	it := s.membersCol(accountID).Documents(ctx)
	defer it.Stop()
	for {
		snap, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return nil, err
		}
		var m Member
		if err := snap.DataTo(&m); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, nil
}

func (s *Store) DeleteMember(ctx context.Context, accountID, uid string) error {
	_, err := s.membersCol(accountID).Doc(uid).Delete(ctx)
	return err
}

// CreateInvite records a pending invite keyed by email (one pending invite per email, globally).
func (s *Store) CreateInvite(ctx context.Context, accountID, email, role string) error {
	id, err := canonical(email)
	if err != nil {
		return err
	}
	inv := Invite{Email: strings.TrimSpace(email), Role: role, AccountID: accountID, CreatedAt: s.clock()}
	_, err = s.invitesCol().Doc(id).Set(ctx, inv)
	return err
}

// FindInvite returns the pending invite for an email, or ErrNotFound.
func (s *Store) FindInvite(ctx context.Context, email string) (*Invite, error) {
	id, err := canonical(email)
	if err != nil {
		return nil, err
	}
	snap, err := s.invitesCol().Doc(id).Get(ctx)
	if status.Code(err) == codes.NotFound {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	var inv Invite
	if err := snap.DataTo(&inv); err != nil {
		return nil, err
	}
	return &inv, nil
}

func (s *Store) DeleteInvite(ctx context.Context, email string) error {
	id, err := canonical(email)
	if err != nil {
		return err
	}
	_, err = s.invitesCol().Doc(id).Delete(ctx)
	return err
}

// MigrateLegacyInto copies the pre-multi-tenant root-level library (spaces/{..}/bins, presets,
// config/settings) into the given account. Runs at most once — guarded by a marker doc — and leaves
// the original root data in place as a backup. Used as a one-shot during the auth cutover.
func (s *Store) MigrateLegacyInto(ctx context.Context, accountID string) error {
	marker := s.fs.Collection("_meta").Doc("legacyMigrated")
	if snap, err := marker.Get(ctx); err == nil && snap.Exists() {
		return nil // already migrated
	} else if err != nil && status.Code(err) != codes.NotFound {
		return err
	}

	a := s.account(accountID)

	// spaces + their bins
	spaceIt := s.fs.Collection("spaces").Documents(ctx)
	defer spaceIt.Stop()
	for {
		sp, err := spaceIt.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return err
		}
		if _, err := a.spacesCol().Doc(sp.Ref.ID).Set(ctx, sp.Data()); err != nil {
			return err
		}
		binIt := sp.Ref.Collection("bins").Documents(ctx)
		for {
			b, err := binIt.Next()
			if err == iterator.Done {
				break
			}
			if err != nil {
				binIt.Stop()
				return err
			}
			if _, err := a.binsCol(sp.Ref.ID).Doc(b.Ref.ID).Set(ctx, b.Data()); err != nil {
				binIt.Stop()
				return err
			}
		}
		binIt.Stop()
	}

	// presets
	presetIt := s.fs.Collection("presets").Documents(ctx)
	defer presetIt.Stop()
	for {
		p, err := presetIt.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return err
		}
		if _, err := a.presetsCol().Doc(p.Ref.ID).Set(ctx, p.Data()); err != nil {
			return err
		}
	}

	// settings singleton
	if snap, err := s.fs.Collection("config").Doc("settings").Get(ctx); err == nil && snap.Exists() {
		if _, err := a.configDoc().Set(ctx, snap.Data()); err != nil {
			return err
		}
	} else if err != nil && status.Code(err) != codes.NotFound {
		return err
	}

	_, err := marker.Set(ctx, map[string]interface{}{"migratedTo": accountID, "at": s.clock()})
	return err
}

// ListInvitesForAccount returns the account's pending (not-yet-accepted) invites.
func (s *Store) ListInvitesForAccount(ctx context.Context, accountID string) ([]Invite, error) {
	out := []Invite{}
	it := s.invitesCol().Where("accountId", "==", accountID).Documents(ctx)
	defer it.Stop()
	for {
		snap, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return nil, err
		}
		var inv Invite
		if err := snap.DataTo(&inv); err != nil {
			return nil, err
		}
		out = append(out, inv)
	}
	return out, nil
}
