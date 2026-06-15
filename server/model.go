package main

// Domain model for the VeggieGrow REST API.
//
// JSON field names match the Android app's Gson serialization exactly, so the existing client
// model objects round-trip without translation. The `firestore` tags control the on-disk layout.
//
// Rev / UpdatedAtEpochMillis are server-authoritative metadata used for optimistic concurrency:
// every write bumps Rev and the value is surfaced as the ETag response header. Clients echo it
// back via If-Match to guard against clobbering a newer revision. Values a client sends in these
// fields are ignored — the server is the sole authority.

// Settings is the global settings singleton (one per library).
type Settings struct {
	PumpRate             float64 `json:"pumpRate" firestore:"pumpRate"`
	PumpRateUnit         string  `json:"pumpRateUnit" firestore:"pumpRateUnit"` // "LPH" | "GPH"
	MinWaterLevel        float64 `json:"minWaterLevel" firestore:"minWaterLevel"`
	HarvestAlertDays     int     `json:"harvestAlertDays" firestore:"harvestAlertDays"`
	UseMockHardware      bool    `json:"useMockHardware" firestore:"useMockHardware"`
	Rev                  int64   `json:"rev" firestore:"rev"`
	UpdatedAtEpochMillis int64   `json:"updatedAtEpochMillis" firestore:"updatedAt"`
}

func (s *Settings) GetRev() int64       { return s.Rev }
func (s *Settings) SetRev(r int64)      { s.Rev = r }
func (s *Settings) SetUpdatedAt(t int64) { s.UpdatedAtEpochMillis = t }

// DefaultSettings mirrors the defaults in the Android Settings class, returned when no settings
// document has been written yet.
func DefaultSettings() Settings {
	return Settings{
		PumpRate:         1000.0,
		PumpRateUnit:     "LPH",
		MinWaterLevel:    1000.0,
		HarvestAlertDays: 3,
		UseMockHardware:  true,
	}
}

// WateringRatePoint is one point in a bin/preset watering schedule.
type WateringRatePoint struct {
	DayOffset int     `json:"dayOffset" firestore:"dayOffset"`
	Rate      float64 `json:"rate" firestore:"rate"`
}

// Bin is a growth bin within a Space. Stored as a document in the space's `bins` subcollection.
type Bin struct {
	Code                   string              `json:"code" firestore:"code"`
	VarietyName            string              `json:"varietyName" firestore:"varietyName"`
	ImageBase64            string              `json:"imageBase64,omitempty" firestore:"imageBase64,omitempty"`
	StartDateEpochMillis   int64               `json:"startDateEpochMillis" firestore:"startDateEpochMillis"`
	WateringSchedule       []WateringRatePoint `json:"wateringSchedule" firestore:"wateringSchedule"`
	FirstHarvestDays       int                 `json:"firstHarvestDays" firestore:"firstHarvestDays"`
	HarvestIntervalDays    int                 `json:"harvestIntervalDays" firestore:"harvestIntervalDays"`
	LastHarvestEpochMillis int64               `json:"lastHarvestEpochMillis" firestore:"lastHarvestEpochMillis"`
	Rev                    int64               `json:"rev" firestore:"rev"`
	UpdatedAtEpochMillis   int64               `json:"updatedAtEpochMillis" firestore:"updatedAt"`
}

func (b *Bin) GetRev() int64        { return b.Rev }
func (b *Bin) SetRev(r int64)       { b.Rev = r }
func (b *Bin) SetUpdatedAt(t int64) { b.UpdatedAtEpochMillis = t }

// Space is a growth space. Its bins live in a subcollection and are populated on read but never
// written through the space document (see firestore:"-" on Bins) — manage them via the bin
// endpoints.
type Space struct {
	Code                  string  `json:"code" firestore:"code"`
	Name                  string  `json:"name" firestore:"name"`
	ImageBase64           string  `json:"imageBase64,omitempty" firestore:"imageBase64,omitempty"`
	WaterReservoirSize    float64 `json:"waterReservoirSize" firestore:"waterReservoirSize"`
	LastRefillEpochMillis int64   `json:"lastRefillEpochMillis" firestore:"lastRefillEpochMillis"`
	Bins                  []Bin   `json:"bins" firestore:"-"`
	Rev                   int64   `json:"rev" firestore:"rev"`
	UpdatedAtEpochMillis  int64   `json:"updatedAtEpochMillis" firestore:"updatedAt"`
}

func (s *Space) GetRev() int64        { return s.Rev }
func (s *Space) SetRev(r int64)       { s.Rev = r }
func (s *Space) SetUpdatedAt(t int64) { s.UpdatedAtEpochMillis = t }

// Preset is a reusable bin template, keyed by name.
type Preset struct {
	Name                 string              `json:"name" firestore:"name"`
	VarietyName          string              `json:"varietyName" firestore:"varietyName"`
	ImageBase64          string              `json:"imageBase64,omitempty" firestore:"imageBase64,omitempty"`
	WateringSchedule     []WateringRatePoint `json:"wateringSchedule" firestore:"wateringSchedule"`
	FirstHarvestDays     int                 `json:"firstHarvestDays" firestore:"firstHarvestDays"`
	HarvestIntervalDays  int                 `json:"harvestIntervalDays" firestore:"harvestIntervalDays"`
	Rev                  int64               `json:"rev" firestore:"rev"`
	UpdatedAtEpochMillis int64               `json:"updatedAtEpochMillis" firestore:"updatedAt"`
}

func (p *Preset) GetRev() int64        { return p.Rev }
func (p *Preset) SetRev(r int64)       { p.Rev = r }
func (p *Preset) SetUpdatedAt(t int64) { p.UpdatedAtEpochMillis = t }

// --- multi-tenant: accounts, members, invites --------------------------------------------------
//
// Each account owns an isolated library at accounts/{id}/{spaces,presets,config}. Membership lives
// in accounts/{id}/members/{uid}; pending invites are top-level (invites/{email}) so a signing-in
// user can be matched by email with a single lookup (no collection-group index needed).

// Account is one tenant's container.
type Account struct {
	ID        string `json:"id" firestore:"-"`
	Name      string `json:"name" firestore:"name"`
	OwnerUID  string `json:"ownerUid" firestore:"ownerUid"`
	CreatedAt int64  `json:"createdAt" firestore:"createdAt"`
}

// Member is a user's membership in an account, keyed by Firebase UID.
type Member struct {
	UID     string `json:"uid" firestore:"uid"`
	Email   string `json:"email" firestore:"email"`
	Role    string `json:"role" firestore:"role"` // owner | editor | viewer
	AddedAt int64  `json:"addedAt" firestore:"addedAt"`
}

// Invite is a pending membership for an email that hasn't joined yet, consumed on first sign-in.
type Invite struct {
	Email     string `json:"email" firestore:"email"`
	Role      string `json:"role" firestore:"role"` // editor | viewer
	AccountID string `json:"accountId" firestore:"accountId"`
	CreatedAt int64  `json:"createdAt" firestore:"createdAt"`
}
