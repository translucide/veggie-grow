# --- build stage -------------------------------------------------------------------------------
# Builds the static binary. No local Go toolchain needed — Cloud Build / Docker runs this.
FROM golang:1.22 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux GOFLAGS=-mod=mod

# Resolve dependencies first for better layer caching.
COPY go.mod ./
COPY *.go ./
RUN go mod tidy
RUN go build -trimpath -ldflags="-s -w" -o /server .

# --- run stage ---------------------------------------------------------------------------------
# distroless/static: no shell, no libc, ~2MB base. Runs as an unprivileged user.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /server /server
ENV PORT=8080
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/server"]
