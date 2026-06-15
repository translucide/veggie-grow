# VeggieGrow REST API

A self-contained Go service backing VeggieGrow. **Multi-tenant**, resource-oriented CRUD over
Firestore with **Firebase Authentication** and role-based access, packaged as a single static binary
and deployed to **Google Cloud Run** (scales to zero; ~$0 at hobby scale).

Each user signs in with Firebase (Google or email/password); the server reads their custom claims
(`accountId`, `role`) to scope every request to one account's isolated library and enforce
permissions (**owner** > **editor** > **viewer**).

## Layout

| File          | Purpose                                                           |
|---------------|------------------------------------------------------------------|
| `main.go`     | Config, Firestore + Firebase clients, middleware, shutdown        |
| `auth.go`     | Firebase ID-token verification, `Principal`, roles, custom claims |
| `handlers.go` | Routes + HTTP handlers (`Server`)                                |
| `store.go`    | Firestore persistence — account-scoped data + accounts/members/invites |
| `model.go`    | Domain structs; data JSON matches the Android Gson model          |
| `httputil.go` | JSON/error helpers, ETag / If-Match                              |
| `Dockerfile`  | Multi-stage build → distroless static image                      |

### Firestore data model

```
accounts/{accountId}                            name, ownerUid, createdAt
accounts/{accountId}/members/{uid}              email, role (owner|editor|viewer)
accounts/{accountId}/spaces/{code}              space fields + rev + updatedAt
accounts/{accountId}/spaces/{code}/bins/{bin}   bin fields   + rev + updatedAt
accounts/{accountId}/presets/{name}             preset fields + rev + updatedAt
accounts/{accountId}/config/settings            settings singleton + rev + updatedAt
invites/{email}                                 pending invite: role, accountId (consumed on sign-in)
```

Data-resource document IDs are the lowercased natural keys (space code, bin code, preset name), so
keys are case-insensitive and must not contain `/`. Account IDs are server-assigned.

## Endpoints

`GET /health` is public. Every `/v1/*` route requires a valid Firebase ID token (see Auth). Data
routes also require an account on the token; **reads** need any member, **writes** need editor+.

**Identity & account** (authenticated; no account required for `/me` and account creation):

| Method & path                              | Action                                              |
|--------------------------------------------|-----------------------------------------------------|
| `GET    /v1/me`                            | Caller identity + account/role, or `needsOnboarding`; auto-accepts a matching invite |
| `POST   /v1/accounts`                      | Create an account; caller becomes its owner         |
| `GET    /v1/account`                       | Current account info                                |
| `GET    /v1/account/members`               | List members + pending invites (any member)         |
| `POST   /v1/account/members`               | Invite by email + role (owner)                      |
| `PUT    /v1/account/members/{uid}`         | Change a member's role (owner)                      |
| `DELETE /v1/account/members/{uid}`         | Remove a member (owner)                             |
| `DELETE /v1/account/invites/{email}`       | Cancel a pending invite (owner)                     |

When the server changes a user's claims (create account, accept invite, role change) the response
includes `tokenStale: true` — the client must force-refresh its Firebase ID token before reusing it.

**Account-scoped data** (reads: any member; writes: editor+):

| Method & path                         | Action                                  |
|---------------------------------------|-----------------------------------------|
| `GET    /health`                     | Liveness probe (public)                 |
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

Firebase Authentication. The client sends `Authorization: Bearer <firebase-id-token>`; the server
verifies it with the Firebase Admin SDK and reads two custom claims it stamps on each user:
`accountId` and `role` (`owner`/`editor`/`viewer`). There is **no static API token** — the Cloud Run
service is publicly reachable but every `/v1` route is gated by token verification.

The server mints claims via the Admin SDK (e.g. on account creation or accepting an invite); the
Cloud Run service account needs `roles/firebaseauth.admin` for this.

## Configuration (env vars)

| Var                    | Default        | Notes                                            |
|------------------------|----------------|--------------------------------------------------|
| `PORT`                 | `8080`         | Set automatically by Cloud Run                   |
| `PROJECT_ID`           | auto-detected  | GCP/Firebase project; also resolved from ADC     |
| `FIRESTORE_EMULATOR_HOST` | _(none)_    | Point at the local emulator for dev              |
| `MIGRATE_LEGACY`       | _(unset)_      | One-shot cutover only: `true` makes the first account absorb pre-multi-tenant root data. Remove after. |

## Run locally (Firestore emulator)

```sh
# 1. Start the emulator (needs the gcloud beta emulators component)
gcloud emulators firestore start --host-port=localhost:8085

# 2. In another shell, run the API against it
export FIRESTORE_EMULATOR_HOST=localhost:8085
export PROJECT_ID=your-project-id   # your actual GCP project — token verification needs it
go run .            # requires Go 1.22+ locally; otherwise use Docker below

# 3. Smoke test (health is public; /v1 needs a real Firebase ID token)
curl -s localhost:8080/health                                    # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/v1/me    # 401 without a token
# To call /v1, grab an ID token from a signed-in client and:
#   curl -s localhost:8080/v1/me -H "Authorization: Bearer $ID_TOKEN"
```

Token verification calls Google's public-key endpoint, so the local server needs the real
`PROJECT_ID` and outbound network even when Firestore is the emulator. No local Go? Build and run the
container instead:

```sh
docker build -t veggiegrow-api .
docker run --rm -p 8080:8080 \
  -e PROJECT_ID=your-project-id -e FIRESTORE_EMULATOR_HOST=host.docker.internal:8085 \
  veggiegrow-api
```

## Deploy to Cloud Run — full runbook

This is the exact, reproducible sequence used to deploy this service from scratch, including the
IAM grants and the two gotchas hit along the way. Run it from this `server/` directory.

### Values to fill in (example placeholders)

| Thing            | Example                                                            |
|------------------|-------------------------------------------------------------------|
| GCP project      | `your-project-id`                                                  |
| Region           | `northamerica-northeast1` (pick one near you)                     |
| Service          | `veggiegrow-api`                                                   |
| Canonical URL    | `https://SERVICE-HASH-REGION.a.run.app` (assigned at deploy)      |
| Account          | `you@example.com`                                                  |
| Auth             | Firebase Authentication (Google + email/password); no static token |

```sh
# Shell variables used throughout
REGION=northamerica-northeast1            # Montréal
SERVICE=veggiegrow-api
```

> **Firebase prerequisite (one-time, console):** add Firebase to the project, enable the
> Email/Password + Google sign-in providers, and register the Android app (downloads
> `app/google-services.json`). See the app README / the project's auth notes.

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
PROJECT=your-project-id
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

# Runtime: Firestore access (WITHOUT this every data request 500s with
# "PermissionDenied: Missing or insufficient permissions") + minting Firebase custom claims.
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member="serviceAccount:$SA" --role="roles/datastore.user"
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member="serviceAccount:$SA" --role="roles/firebaseauth.admin"
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
  --set-env-vars "PROJECT_ID=$PROJECT" \
  --quiet
```

`--allow-unauthenticated` lets requests reach the container; **token verification in the app is what
actually gates access** (`--allow-unauthenticated` only controls Cloud Run's own IAM layer, which we
don't use here). The first source deploy also auto-creates an Artifact Registry repo
(`cloud-run-source-deploy`).

### 6. Get the URL and verify

The deploy prints a Service URL. Prefer the **canonical** `*.a.run.app` URL reported by
`status.url` (the project-number form `*-<PROJECT_NUMBER>.<region>.run.app` can route inconsistently
right after the first deploy):

```sh
URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')
echo "$URL"

curl -s "$URL/health"                                          # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' "$URL/v1/me"          # 401 (no token)
curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'Authorization: Bearer bogus' "$URL/v1/me"                # 401 (invalid token)
# Authenticated calls need a real Firebase ID token from a signed-in client:
#   curl -s "$URL/v1/me" -H "Authorization: Bearer $ID_TOKEN"
```

### 7. (Optional) custom domain

Map `subdomain.domain.com` to the service via **Cloud Run → Manage custom domains**, then set
`assets/sync_config.json` `baseUrl` to `https://subdomain.domain.com/v1/`.

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

## How the Android app uses this API

`app/src/main/assets/sync_config.json` holds only `baseUrl` (the API root ending in `/v1/`). The old
`authHeader` static token is **dead** — auth is now per-user Firebase ID tokens.

**Auth & onboarding** (`ui/auth/AuthActivity`, `data/AccountManager`, `data/FirebaseTokenProvider`):

- The launcher `AuthActivity` gates the app: FirebaseUI sign-in (email or Google) → `GET /v1/me`. A
  member opens the app; an invitee is auto-joined on `/me`; a brand-new user onboards by creating a
  library (becoming owner).
- `ApiClient` sends `Authorization: Bearer <Firebase ID token>` on every call; `tokenStale` responses
  trigger a forced token refresh.
- `ui/auth/MembersActivity` (owner-only, from Settings) lists members/invites and invites / re-roles /
  removes people.

**Sync** (`data/ApiClient` + `data/CloudSync`), unchanged except it's account-scoped and gated on
sign-in (`CloudSync.setEnabled`):

- **Write-through.** Each `DataRepository` mutation becomes a single `PUT`/`DELETE` on a one-thread
  executor (FIFO order); a failed write is parked and retried.
- **Pull.** Foreground polling every 20s (and on resume) replaces the local model only when the
  server changed; skipped while writes are pending.
- **Bootstrap.** First foreground after onboarding seeds an empty account from the local library, or
  pulls the existing one.
- **Renames** delete the old key and create the new one, so no orphan is left.

Last-write-wins per resource (no client `If-Match` yet); per-resource granularity keeps real
conflicts rare.
