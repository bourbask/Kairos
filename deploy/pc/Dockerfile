# syntax=docker/dockerfile:1
# Tentacules hébergés à côté d'un service local (poste de travail), hors VPS.

# ---- Build ----
FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release -p kairos-medias -p kairos-documents

# ---- Runtime ----
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates poppler-utils unzip tesseract-ocr tesseract-ocr-fra \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/kairos-medias /src/target/release/kairos-documents /app/
# Bases (suivi des demandes, index des documents) : volumes nommés, propriété de l'utilisateur d'exécution.
RUN mkdir -p /data && chown 1001:1001 /data
USER 1001:1001
ENTRYPOINT ["/app/kairos-medias"]
