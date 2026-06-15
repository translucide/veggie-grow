package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"cloud.google.com/go/firestore"
)

// Config is the runtime configuration, sourced entirely from environment variables so the same
// binary runs locally (against the Firestore emulator) and on Cloud Run unchanged.
type Config struct {
	Port      string
	ProjectID string
	// MigrateLegacy: when true, creating the first account copies the old root-level library into it
	// (one-shot cutover helper). Remove the env var after the migration has run.
	MigrateLegacy bool
}

func loadConfig() Config {
	project := firstNonEmpty(
		os.Getenv("GOOGLE_CLOUD_PROJECT"),
		os.Getenv("FIRESTORE_PROJECT"),
		os.Getenv("PROJECT_ID"),
	)
	if project == "" {
		// Resolve from Application Default Credentials / metadata server.
		project = firestore.DetectProjectID
	}
	return Config{
		Port:          firstNonEmpty(os.Getenv("PORT"), "8080"),
		ProjectID:     project,
		MigrateLegacy: os.Getenv("MIGRATE_LEGACY") == "true",
	}
}

func main() {
	cfg := loadConfig()

	ctx := context.Background()
	fs, err := firestore.NewClient(ctx, cfg.ProjectID)
	if err != nil {
		log.Fatalf("firestore: %v", err)
	}
	defer fs.Close()

	// Firebase wants a concrete project ID; the Firestore "detect" sentinel means "auto-detect from
	// ADC", which Firebase does itself when given an empty string.
	fbProject := cfg.ProjectID
	if fbProject == firestore.DetectProjectID {
		fbProject = ""
	}
	authClient, err := newAuthClient(ctx, fbProject)
	if err != nil {
		log.Fatalf("firebase auth: %v", err)
	}

	store := NewStore(fs, func() int64 { return time.Now().UnixMilli() })
	srv := &Server{store: store, auth: authClient, migrateLegacy: cfg.MigrateLegacy}

	handler := withRecover(withLogging(srv.routes()))
	httpServer := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// Graceful shutdown on SIGTERM (Cloud Run sends it before stopping the instance).
	idleClosed := make(chan struct{})
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := httpServer.Shutdown(shutdownCtx); err != nil {
			log.Printf("graceful shutdown: %v", err)
		}
		close(idleClosed)
	}()

	log.Printf("VeggieGrow API listening on :%s", cfg.Port)
	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("listen: %v", err)
	}
	<-idleClosed
}

// withLogging logs one line per request with method, path, status and latency.
func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}

// withRecover turns a panicking handler into a 500 instead of crashing the process.
func withRecover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("panic: %v", rec)
				writeError(w, http.StatusInternalServerError, "internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
