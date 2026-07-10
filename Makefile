.PHONY: build test check scrape briefing status clean fmt clippy

build:
	cargo build --release

test:
	cargo test

check:
	cargo check

fmt:
	cargo fmt

clippy:
	cargo clippy -- -D warnings

scrape:
	cargo run -- scrape

briefing:
	cargo run -- briefing

status:
	cargo run -- status

clean:
	cargo clean

# Cross-compilation Linux x86_64.
build-linux:
	cargo build --release --target x86_64-unknown-linux-gnu

# Déploiement sur VPS (configurer VPS_HOST dans .env ou en variable)
deploy:
	@echo "Usage: VPS_HOST=user@vps make deploy"
	scp target/release/kairos $(VPS_HOST):~/bin/kairos
