# VeggieGrow REST API

A self-contained Go service backing the VeggieGrow shared library. Resource-oriented CRUD over
Firestore, packaged as a single static binary and deployed to **Google Cloud Run** (scales to zero;
~$0 at hobby scale).

Replaces the old whole-document `GET/PUT veggiegrow.json` blob-sync with real per-resource
endpoints.

## Layout

| File          | Purpose                                                           |
|---------------|------------------------------------------------------------------|
| `main.go`     | Config, Firestore client, middleware (auth/logging/recover), shutdown |
| `handlers.go` | Routes + HTTP handlers (`Server`)                                |
| `store.go`    | Firestore persistence (`Store`) with optimistic concurrency      |
| `model.go`    | Domain structs; JSON field names match the Android Gson model     |
| `httputil.go` | JSON/error helpers, ETag / If-Match                              |
| `Dockerfile`  | Multi-stage build → distroless static image                      |

### Firestore data model

```
spaces/{code}                      space fields + rev + updatedAt
spaces/{code}/bins/{binCode}       bin fields  + rev + updatedAt
presets/{name}                     preset fields + rev + updatedAt
config/settings                    settings singleton + rev + updatedAt
```

Document IDs are the lowercased natural keys (space code, bin code, preset name), so keys are
case-insensitive and must not contain `/`.

## Endpoints

All `/v1/*` routes require the `Authorization` header (see Auth). `GET /health` is public.

| Method & path                         | Action                                  |
|---------------------------------------|-----------------------------------------|
| `GET    /health`                     | Liveness probe                          |
| `GET    /v1/spaces`                   | List spaces (each with its bins)        |
| `POST   /v1/spaces`                   | Create a space (409 if it exists)       |
| `GET    /v1/spaces/{code}`            | Get a space + its bins                  |
| `PUT    /v1/spaces/{code}`            | Upsert a space (bins untouched)         |
| `DELETE /v1/spaces/{code}`            | Delete a space and its bins             |
| `GET    /v1/spaces/{code}/bins`       | List bins in a space                    |
| `POST   /v1/spaces/{code}/bins`       | Create a bin                            |
| `GET    /v1/spaces/{code}/bins/{bin}` | Get a bin                               |
| `PUT    /v1/spaces/{code}/bins/{bin}` | Upsert a bin                            |
| `DELETE /v1/spaces/{code}/bins/{bin}` | Delete a bin                            |
| `GET    /v1/presets`                  | List presets                            |
| `POST   /v1/presets`                  | Create a preset                         |
| `GET    /v1/presets/{name}`           | Get a preset                            |
| `PUT    /v1/presets/{name}`           | Upsert a preset                         |
| `DELETE /v1/presets/{name}`           | Delete a preset                         |
| `GET    /v1/settings`                 | Get settings (defaults if never set)    |
| `PUT    /v1/settings`                 | Update settings                         |

### Optimistic concurrency

Every resource carries a `rev`, surfaced as the `ETag` response header (e.g. `ETag: "4"`). To make a
safe update, send the rev you last saw in `If-Match`:

```
PUT /v1/spaces/a
If-Match: "4"
```

If the stored rev no longer matches, the server returns **412 Precondition Failed** and writes
nothing. Omit `If-Match` for last-write-wins.

## Auth

Set `AUTH_TOKEN`. Requests must send it as `Authorization: <token>` (the verbatim form the Android
client already uses) or `Authorization: Bearer <token>`. If `AUTH_TOKEN` is unset the API runs open
(local dev only).

## Configuration (env vars)

| Var                    | Default        | Notes                                            |
|------------------------|----------------|--------------------------------------------------|
| `PORT`                 | `8080`         | Set automatically by Cloud Run                   |
| `AUTH_TOKEN`           | _(none)_       | Static API token; unset = open                   |
| `GOOGLE_CLOUD_PROJECT` | auto-detected  | Firestore project; resolved from ADC on Cloud Run|
| `FIRESTORE_EMULATOR_HOST` | _(none)_    | Point at the local emulator for dev              |

## Run locally (Firestore emulator)

```sh
# 1. Start the emulator (needs the gcloud beta emulators component)
gcloud emulators firestore start --host-port=localhost:8085

# 2. In another shell, run the API against it
export FIRESTORE_EMULATOR_HOST=localhost:8085
export PROJECT_ID=veggiegrow-local
export AUTH_TOKEN=devtoken
go run .            # requires Go 1.22+ locally; otherwise use Docker below

# 3. Smoke test
curl -s localhost:8080/health
curl -s -X PUT localhost:8080/v1/spaces/a \
  -H 'Authorization: devtoken' -H 'Content-Type: application/json' \
  -d '{"name":"My Growth Space","waterReservoirSize":20000}'
curl -s localhost:8080/v1/spaces -H 'Authorization: devtoken'
```

No local Go? Build and run the container instead:

```sh
docker build -t veggiegrow-api .
docker run --rm -p 8080:8080 -e AUTH_TOKEN=devtoken \
  -e PROJECT_ID=veggiegrow-local -e FIRESTORE_EMULATOR_HOST=host.docker.internal:8085 \
  veggiegrow-api
```

## Deploy to Cloud Run — full runbook

This is the exact, reproducible sequence used to deploy this service from scratch, including the
IAM grants and the two gotchas hit along the way. Run it from this `server/` directory.

### What was deployed (current values)

| Thing            | Value                                                              |
|------------------|-------------------------------------------------------------------|
| GCP project      | `project-a76464bf-d7c3-49a7-920` ("Veggie Grow")                  |
| Region           | `northamerica-northeast1` (Montréal)                              |
| Service          | `veggiegrow-api`                                                   |
| Canonical URL    | `https://veggiegrow-api-d5ddwcubya-nn.a.run.app`                  |
| Account          | `info@translucide.ca`                                              |
| Auth token       | `AUTH_TOKEN` env (matches `assets/sync_config.json` `authHeader`) |

```sh
# Shell variables used throughout
REGION=northamerica-northeast1            # Montréal
SERVICE=veggiegrow-api
AUTH_TOKEN=Cj555hjE2bIT056vfBg66444       # must match assets/sync_config.json authHeader
```

### 1. Pick (or create) a project

```sh
# Who am I, and which project (if any) is active?
gcloud auth list --format='value(account)'
gcloud config get-value project

# List projects this account can use; copy the full projectId from the output.
gcloud projects list --format='table(projectId,name,projectNumber)'

# A truncated table can hide the full ID — fetch it by name if needed:
gcloud projects list --filter='name:"Veggie Grow"' --format='value(projectId)'
```

Use the existing project:

```sh
PROJECT=project-a76464bf-d7c3-49a7-920
gcloud config set project "$PROJECT"
```

…**or** create a fresh one (needs a billing account linked before Cloud Run/Firestore will work):

```sh
PROJECT=veggiegrow-$RANDOM
gcloud projects create "$PROJECT" --name="Veggie Grow"
gcloud config set project "$PROJECT"
gcloud billing accounts list                                  # find your billing account id
gcloud billing projects link "$PROJECT" --billing-account=XXXXXX-XXXXXX-XXXXXX
```

### 2. Enable the required APIs

```sh
gcloud services enable \
  run.googleapis.com \
  firestore.googleapis.com \
  cloudbuild.googleapis.com
```

### 3. Create the Firestore database (Native mode, once per project)

```sh
gcloud firestore databases create --location="$REGION"
```

### 4. Grant IAM to the default compute service account

`gcloud run deploy --source` builds with Cloud Build and runs as the **default compute service
account** (`<PROJECT_NUMBER>-compute@developer.gserviceaccount.com`). On newer projects this account
starts with **no** roles, so both the build and the running container fail until you grant them.
Get the account, then grant the build-time and runtime roles:

```sh
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

# Build-time (Cloud Build reads the uploaded source, pushes the image, writes logs)
for ROLE in \
  roles/cloudbuild.builds.builder \
  roles/storage.objectViewer \
  roles/artifactregistry.writer \
  roles/logging.logWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT" \
    --member="serviceAccount:$SA" --role="$ROLE"
done

# Runtime (the container talks to Firestore) — WITHOUT this every request 500s with
# "PermissionDenied: Missing or insufficient permissions."
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member="serviceAccount:$SA" --role="roles/datastore.user"
```

Verify the roles landed (IAM can take a minute to propagate):

```sh
gcloud projects get-iam-policy "$PROJECT" \
  --flatten='bindings[].members' \
  --filter="bindings.members:$SA" \
  --format='value(bindings.role)'
```

### 5. Deploy from source

No local Go or Docker toolchain needed — Cloud Build builds the Dockerfile:

```sh
gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --set-env-vars "AUTH_TOKEN=$AUTH_TOKEN" \
  --quiet
```

`--allow-unauthenticated` exposes the URL publicly; the app's own `AUTH_TOKEN` is what actually gates
access. The first source deploy also auto-creates an Artifact Registry repo
(`cloud-run-source-deploy`).

### 6. Get the URL and verify

The deploy prints a Service URL. Prefer the **canonical** `*.a.run.app` URL reported by
`status.url` (the project-number form `*-<PROJECT_NUMBER>.<region>.run.app` can route inconsistently
right after the first deploy):

```sh
URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')
echo "$URL"

curl -s "$URL/health"                                          # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' "$URL/v1/spaces"      # 401 (no token)
curl -s -X PUT "$URL/v1/spaces/A" \
  -H "Authorization: $AUTH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"My Growth Space","waterReservoirSize":20000}'
curl -s "$URL/v1/spaces" -H "Authorization: $AUTH_TOKEN"
curl -s -X DELETE "$URL/v1/spaces/A" -H "Authorization: $AUTH_TOKEN"  # cleanup
```

### 7. (Optional) custom domain

Map `veggiegrow.translucide.ca` to the service via **Cloud Run → Manage custom domains**, then set
`assets/sync_config.json` `baseUrl` to `https://veggiegrow.translucide.ca/v1/`.

### Redeploy / update

After changing code, just re-run step 5. Env vars persist across deploys unless you pass
`--set-env-vars` (which replaces them) or `--update-env-vars` (which merges).

### Operations cheat-sheet

```sh
# Tail logs
gcloud run services logs read "$SERVICE" --region "$REGION" --limit 50

# Inspect status / traffic / URL
gcloud run services describe "$SERVICE" --region "$REGION" \
  --format='yaml(status.conditions, status.url, status.traffic)'

# Check public-invoker binding
gcloud run services get-iam-policy "$SERVICE" --region "$REGION"

# Roll back to a previous revision
gcloud run services update-traffic "$SERVICE" --region "$REGION" --to-revisions=REVISION=100
```

### Gotchas hit during the first deploy

1. **Build fails: "default service account is missing required IAM permissions" / `could not
   resolve source … permission denied`.** The compute SA lacked build roles — fixed by step 4
   (`cloudbuild.builds.builder` + `storage.objectViewer` + `artifactregistry.writer` +
   `logging.logWriter`).
2. **Every API call returns 500 `PermissionDenied: Missing or insufficient permissions`.** The
   running container couldn't reach Firestore — fixed by granting `roles/datastore.user` (step 4).
3. **`/healthz` returns a Google 404 page that never reaches the container.** Cloud Run's edge
   intercepts the exact path `/healthz`; all other paths pass through. The health route is therefore
   **`/health`**, not `/healthz`.
4. **The project-number URL (`*-<PROJECT_NUMBER>.<region>.run.app`) was flaky right after the first
   deploy** (intermittent edge 404/401). The canonical `*.a.run.app` URL from `status.url` was
   reliable — use that.

## Connecting the Android app

The current client (`SyncConfig` / `CloudSync`) does whole-document `GET`/`PUT` of `veggiegrow.json`.
Moving it to these endpoints is a separate client-side change:

1. Point `assets/sync_config.json` `baseUrl` at `https://<cloud-run-url>/v1/` and keep the existing
   `authHeader` token (set the same value as `AUTH_TOKEN`).
2. Rewrite `CloudSync` to call the resource endpoints (list/get/put per space, bin, preset, and
   settings) and use `ETag`/`If-Match` for the take-turns conflict check instead of the global
   `revision` counter.

That rewrite isn't included here — ask and it can be done as the next step.
