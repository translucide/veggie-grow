package main

import (
	"net/http"
	"net/url"
	"strings"
)

// Server wires the store and auth token to the HTTP routes.
type Server struct {
	store     *Store
	authToken string
}

// routes builds the request handler. Everything under /v1 is gated by the auth middleware;
// /healthz is intentionally public so Cloud Run / uptime checks can probe it.
func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	// Note: use /health, not /healthz — Cloud Run's edge intercepts /healthz and 404s it before
	// it reaches the container.
	mux.HandleFunc("GET /health", s.handleHealth)

	api := http.NewServeMux()
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

	mux.Handle("/v1/", s.auth(api))
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// --- spaces ------------------------------------------------------------------------------------

func (s *Server) listSpaces(w http.ResponseWriter, r *http.Request) {
	spaces, err := s.store.ListSpaces(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"spaces": spaces})
}

func (s *Server) createSpace(w http.ResponseWriter, r *http.Request) {
	var sp Space
	if !decodeBody(w, r, &sp) {
		return
	}
	if strings.TrimSpace(sp.Code) == "" {
		writeError(w, http.StatusBadRequest, "code is required")
		return
	}
	out, err := s.store.CreateSpace(r.Context(), &sp)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	w.Header().Set("Location", "/v1/spaces/"+url.PathEscape(strings.ToLower(out.Code)))
	writeJSON(w, http.StatusCreated, out)
}

func (s *Server) getSpace(w http.ResponseWriter, r *http.Request) {
	out, err := s.store.GetSpace(r.Context(), r.PathValue("code"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putSpace(w http.ResponseWriter, r *http.Request) {
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var sp Space
	if !decodeBody(w, r, &sp) {
		return
	}
	out, err := s.store.PutSpace(r.Context(), r.PathValue("code"), &sp, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deleteSpace(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeleteSpace(r.Context(), r.PathValue("code")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- bins --------------------------------------------------------------------------------------

func (s *Server) listBins(w http.ResponseWriter, r *http.Request) {
	bins, err := s.store.ListBins(r.Context(), r.PathValue("code"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"bins": bins})
}

func (s *Server) createBin(w http.ResponseWriter, r *http.Request) {
	var b Bin
	if !decodeBody(w, r, &b) {
		return
	}
	if strings.TrimSpace(b.Code) == "" {
		writeError(w, http.StatusBadRequest, "code is required")
		return
	}
	space := r.PathValue("code")
	out, err := s.store.CreateBin(r.Context(), space, &b)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	w.Header().Set("Location", "/v1/spaces/"+url.PathEscape(strings.ToLower(space))+"/bins/"+url.PathEscape(strings.ToLower(out.Code)))
	writeJSON(w, http.StatusCreated, out)
}

func (s *Server) getBin(w http.ResponseWriter, r *http.Request) {
	out, err := s.store.GetBin(r.Context(), r.PathValue("code"), r.PathValue("bin"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putBin(w http.ResponseWriter, r *http.Request) {
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var b Bin
	if !decodeBody(w, r, &b) {
		return
	}
	out, err := s.store.PutBin(r.Context(), r.PathValue("code"), r.PathValue("bin"), &b, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deleteBin(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeleteBin(r.Context(), r.PathValue("code"), r.PathValue("bin")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- presets -----------------------------------------------------------------------------------

func (s *Server) listPresets(w http.ResponseWriter, r *http.Request) {
	presets, err := s.store.ListPresets(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"presets": presets})
}

func (s *Server) createPreset(w http.ResponseWriter, r *http.Request) {
	var p Preset
	if !decodeBody(w, r, &p) {
		return
	}
	if strings.TrimSpace(p.Name) == "" {
		writeError(w, http.StatusBadRequest, "name is required")
		return
	}
	out, err := s.store.CreatePreset(r.Context(), &p)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	w.Header().Set("Location", "/v1/presets/"+url.PathEscape(strings.ToLower(out.Name)))
	writeJSON(w, http.StatusCreated, out)
}

func (s *Server) getPreset(w http.ResponseWriter, r *http.Request) {
	out, err := s.store.GetPreset(r.Context(), r.PathValue("name"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putPreset(w http.ResponseWriter, r *http.Request) {
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var p Preset
	if !decodeBody(w, r, &p) {
		return
	}
	out, err := s.store.PutPreset(r.Context(), r.PathValue("name"), &p, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deletePreset(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeletePreset(r.Context(), r.PathValue("name")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- settings ----------------------------------------------------------------------------------

func (s *Server) getSettings(w http.ResponseWriter, r *http.Request) {
	out, err := s.store.GetSettings(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) putSettings(w http.ResponseWriter, r *http.Request) {
	ifMatch, ok := parseIfMatch(w, r)
	if !ok {
		return
	}
	var st Settings
	if !decodeBody(w, r, &st) {
		return
	}
	out, err := s.store.PutSettings(r.Context(), &st, ifMatch)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	setETag(w, out.Rev)
	writeJSON(w, http.StatusOK, out)
}
