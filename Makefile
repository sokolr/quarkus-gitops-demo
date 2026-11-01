.PHONY: up down release verify

up:
	@bash scripts/up.sh

down:
	@bash scripts/down.sh

release:
	@bash scripts/release.sh "$(SERVICE)"

verify:
	@bash scripts/verify.sh
