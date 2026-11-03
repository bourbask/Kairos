.PHONY: build test check scrape briefing status clean fmt clippy deploy build-linux

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

# Déploiement complet sur VPS : build release + copie binaire + profile.toml + units systemd.
# Usage : VPS_HOST=user@vps make deploy
deploy:
	VPS_HOST=$(VPS_HOST) ./deploy/deploy.sh
