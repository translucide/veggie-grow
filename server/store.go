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

// canonical normalises a natural key (space/bin code, preset name) into a Firestore document ID.
// Keys are case-insensitive; we reject the handful of values Firestore forbids as document IDs.
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

// --- spaces ------------------------------------------------------------------------------------

func (s *Store) spacesCol() *firestore.CollectionRef { return s.fs.Collection("spaces") }
func (s *Store) presetsCol() *firestore.CollectionRef { return s.fs.Collection("presets") }
func (s *Store) binsCol(spaceID string) *firestore.CollectionRef {
	return s.spacesCol().Doc(spaceID).Collection("bins")
}

func (s *Store) ListSpaces(ctx context.Context) ([]Space, error) {
	out := []Space{}
	it := s.spacesCol().Documents(ctx)
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
		bins, err := s.listBinsByID(ctx, snap.Ref.ID)
		if err != nil {
			return nil, err
		}
		sp.Bins = bins
		out = append(out, sp)
	}
	return out, nil
}

func (s *Store) GetSpace(ctx context.Context, code string) (*Space, error) {
	id, err := canonical(code)
	if err != nil {
		return nil, err
	}
	sp, err := getDoc[Space](ctx, s.spacesCol(), id)
	if err != nil {
		return nil, err
	}
	bins, err := s.listBinsByID(ctx, id)
	if err != nil {
		return nil, err
	}
	sp.Bins = bins
	return sp, nil
}

func (s *Store) CreateSpace(ctx context.Context, sp *Space) (*Space, error) {
	id, err := canonical(sp.Code)
	if err != nil {
		return nil, err
	}
	if err := createDoc(ctx, s.spacesCol(), id, sp, s.clock()); err != nil {
		return nil, err
	}
	sp.Bins = []Bin{}
	return sp, nil
}

func (s *Store) PutSpace(ctx context.Context, code string, sp *Space, ifMatch *int64) (*Space, error) {
	id, err := canonical(code)
	if err != nil {
		return nil, err
	}
	sp.Code = code // the path is authoritative for identity
	if err := putDoc(ctx, s.fs, s.spacesCol().Doc(id), sp, ifMatch, s.clock()); err != nil {
		return nil, err
	}
	bins, err := s.listBinsByID(ctx, id)
	if err != nil {
		return nil, err
	}
	sp.Bins = bins
	return sp, nil
}

func (s *Store) DeleteSpace(ctx context.Context, code string) error {
	id, err := canonical(code)
	if err != nil {
		return err
	}
	if err := s.requireSpace(ctx, id); err != nil {
		return err
	}
	// Delete the bins subcollection first (Firestore does not cascade).
	it := s.binsCol(id).Documents(ctx)
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
	_, err = s.spacesCol().Doc(id).Delete(ctx)
	return err
}

func (s *Store) requireSpace(ctx context.Context, spaceID string) error {
	_, err := s.spacesCol().Doc(spaceID).Get(ctx)
	if status.Code(err) == codes.NotFound {
		return ErrNotFound
	}
	return err
}

// --- bins --------------------------------------------------------------------------------------

func (s *Store) listBinsByID(ctx context.Context, spaceID string) ([]Bin, error) {
	out := []Bin{}
	it := s.binsCol(spaceID).Documents(ctx)
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

func (s *Store) ListBins(ctx context.Context, spaceCode string) ([]Bin, error) {
	sid, err := canonical(spaceCode)
	if err != nil {
		return nil, err
	}
	if err := s.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	return s.listBinsByID(ctx, sid)
}

func (s *Store) GetBin(ctx context.Context, spaceCode, binCode string) (*Bin, error) {
	sid, bid, err := s.binIDs(spaceCode, binCode)
	if err != nil {
		return nil, err
	}
	if err := s.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	return getDoc[Bin](ctx, s.binsCol(sid), bid)
}

func (s *Store) CreateBin(ctx context.Context, spaceCode string, b *Bin) (*Bin, error) {
	sid, bid, err := s.binIDs(spaceCode, b.Code)
	if err != nil {
		return nil, err
	}
	if err := s.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	if err := createDoc(ctx, s.binsCol(sid), bid, b, s.clock()); err != nil {
		return nil, err
	}
	return b, nil
}

func (s *Store) PutBin(ctx context.Context, spaceCode, binCode string, b *Bin, ifMatch *int64) (*Bin, error) {
	sid, bid, err := s.binIDs(spaceCode, binCode)
	if err != nil {
		return nil, err
	}
	if err := s.requireSpace(ctx, sid); err != nil {
		return nil, err
	}
	b.Code = binCode
	if err := putDoc(ctx, s.fs, s.binsCol(sid).Doc(bid), b, ifMatch, s.clock()); err != nil {
		return nil, err
	}
	return b, nil
}

func (s *Store) DeleteBin(ctx context.Context, spaceCode, binCode string) error {
	sid, bid, err := s.binIDs(spaceCode, binCode)
	if err != nil {
		return err
	}
	if err := s.requireSpace(ctx, sid); err != nil {
		return err
	}
	ref := s.binsCol(sid).Doc(bid)
	if _, err := ref.Get(ctx); status.Code(err) == codes.NotFound {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	_, err = ref.Delete(ctx)
	return err
}

func (s *Store) binIDs(spaceCode, binCode string) (string, string, error) {
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

func (s *Store) ListPresets(ctx context.Context) ([]Preset, error) {
	out := []Preset{}
	it := s.presetsCol().Documents(ctx)
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

func (s *Store) GetPreset(ctx context.Context, name string) (*Preset, error) {
	id, err := canonical(name)
	if err != nil {
		return nil, err
	}
	return getDoc[Preset](ctx, s.presetsCol(), id)
}

func (s *Store) CreatePreset(ctx context.Context, p *Preset) (*Preset, error) {
	id, err := canonical(p.Name)
	if err != nil {
		return nil, err
	}
	if err := createDoc(ctx, s.presetsCol(), id, p, s.clock()); err != nil {
		return nil, err
	}
	return p, nil
}

func (s *Store) PutPreset(ctx context.Context, name string, p *Preset, ifMatch *int64) (*Preset, error) {
	id, err := canonical(name)
	if err != nil {
		return nil, err
	}
	p.Name = name
	if err := putDoc(ctx, s.fs, s.presetsCol().Doc(id), p, ifMatch, s.clock()); err != nil {
		return nil, err
	}
	return p, nil
}

func (s *Store) DeletePreset(ctx context.Context, name string) error {
	id, err := canonical(name)
	if err != nil {
		return err
	}
	ref := s.presetsCol().Doc(id)
	if _, err := ref.Get(ctx); status.Code(err) == codes.NotFound {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	_, err = ref.Delete(ctx)
	return err
}

// --- settings singleton ------------------------------------------------------------------------

func (s *Store) configDoc() *firestore.DocumentRef {
	return s.fs.Collection("config").Doc("settings")
}

func (s *Store) GetSettings(ctx context.Context) (*Settings, error) {
	snap, err := s.configDoc().Get(ctx)
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

func (s *Store) PutSettings(ctx context.Context, st *Settings, ifMatch *int64) (*Settings, error) {
	if err := putDoc(ctx, s.fs, s.configDoc(), st, ifMatch, s.clock()); err != nil {
		return nil, err
	}
	return st, nil
}
