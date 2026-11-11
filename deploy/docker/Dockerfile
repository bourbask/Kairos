# syntax=docker/dockerfile:1

# ---- Build ----
FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release -p kairos -p kairos-memoire -p kairos-agenda -p kairos-finances -p kairos-alimentation -p kairos-marches -p kairos-partage -p tete

# ---- Runtime ----
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates libssl3 curl \
 && rm -rf /var/lib/apt/lists/*
# supercronic : planificateur cron pour conteneur.
# Version et empreinte épinglées (SHA1 publiée en amont vérifiée) : à changer ensemble.
ARG SUPERCRONIC_VERSION=v0.2.33
ARG SUPERCRONIC_SHA256=feefa310da569c81b99e1027b86b27b51e6ee9ab647747b49099645120cfc671
RUN curl -fsSL -o /usr/local/bin/supercronic \
      "https://github.com/aptible/supercronic/releases/download/${SUPERCRONIC_VERSION}/supercronic-linux-amd64" \
 && echo "${SUPERCRONIC_SHA256}  /usr/local/bin/supercronic" | sha256sum -c - \
 && chmod +x /usr/local/bin/supercronic
WORKDIR /app
# Répertoires des données des tentacules : propriété de l'utilisateur d'exécution (hérité par le volume nommé).
RUN mkdir -p /app/memoire /app/alimentation /app/marches /app/partage && chown 1001:1001 /app/memoire /app/alimentation /app/marches /app/partage
COPY --from=build /src/target/release/kairos /src/target/release/kairos-memoire /src/target/release/kairos-agenda /src/target/release/kairos-finances /src/target/release/kairos-alimentation /src/target/release/kairos-marches /src/target/release/kairos-partage /src/target/release/tete /app/
COPY deploy/docker/supercronic.crontab /app/supercronic.crontab
# config/profile.toml monté en volume sur /app/config ; data + briefings via env (volumes).
ENTRYPOINT ["/app/kairos"]
