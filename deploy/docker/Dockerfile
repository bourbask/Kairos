# syntax=docker/dockerfile:1

# ---- Build ----
FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release --bin kairos

# ---- Runtime ----
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates libssl3 curl \
 && rm -rf /var/lib/apt/lists/*
# supercronic : planificateur cron pour conteneur.
# ponytail: version épinglée ; ajouter le sha256 pour la vérif d'intégrité.
ARG SUPERCRONIC_VERSION=v0.2.33
RUN curl -fsSL -o /usr/local/bin/supercronic \
      "https://github.com/aptible/supercronic/releases/download/${SUPERCRONIC_VERSION}/supercronic-linux-amd64" \
 && chmod +x /usr/local/bin/supercronic
WORKDIR /app
COPY --from=build /src/target/release/kairos /app/kairos
COPY deploy/docker/supercronic.crontab /app/supercronic.crontab
# config/profile.toml monté en volume sur /app/config ; data + briefings via env (volumes).
ENTRYPOINT ["/app/kairos"]
