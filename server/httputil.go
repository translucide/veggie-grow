package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
)

// writeJSON serialises v as the response body with the given status code.
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		if err := json.NewEncoder(w).Encode(v); err != nil {
			log.Printf("encode response: %v", err)
		}
	}
}

// writeError emits a uniform JSON error envelope.
func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]any{"status": status, "message": message},
	})
}

// writeStoreError maps a store sentinel error to the matching HTTP status.
func writeStoreError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		writeError(w, http.StatusNotFound, "resource not found")
	case errors.Is(err, ErrConflict):
		writeError(w, http.StatusConflict, "resource already exists")
	case errors.Is(err, ErrPrecondition):
		writeError(w, http.StatusPreconditionFailed, "stale If-Match: the resource was modified by someone else")
	case errors.Is(err, ErrInvalidKey):
		writeError(w, http.StatusBadRequest, "invalid resource key (must be non-empty and contain no '/')")
	default:
		log.Printf("internal error: %v", err)
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}

// decodeBody reads the JSON request body into dst; on failure it writes a 400 and returns false.
func decodeBody(w http.ResponseWriter, r *http.Request, dst any) bool {
	if err := json.NewDecoder(r.Body).Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON body: "+err.Error())
		return false
	}
	return true
}

// setETag publishes a resource's revision as a (weak-free) ETag header.
func setETag(w http.ResponseWriter, rev int64) {
	w.Header().Set("ETag", fmt.Sprintf(`"%d"`, rev))
}

// parseIfMatch reads an optional If-Match header as a revision number. A missing header (or "*")
// means "no precondition" and yields (nil, true). A malformed header writes a 400 and returns
// (nil, false).
func parseIfMatch(w http.ResponseWriter, r *http.Request) (*int64, bool) {
	raw := strings.TrimSpace(r.Header.Get("If-Match"))
	if raw == "" || raw == "*" {
		return nil, true
	}
	raw = strings.TrimPrefix(raw, "W/")
	raw = strings.Trim(raw, `"`)
	n, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid If-Match header (expected a revision number)")
		return nil, false
	}
	return &n, true
}
