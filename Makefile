-include .env
export

COMPOSE := docker compose

.PHONY: up down migrate seed run test blast

up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down -v

migrate:
	./gradlew --quiet bootRun --args='--migrate-only'

seed:
	$(COMPOSE) exec -T db psql -v ON_ERROR_STOP=1 -U transfers -d transfers < scripts/seed.sql

run:
	./gradlew bootRun

test:
	./gradlew test

# usage: make blast ARGS="20 --amount 3000000 --type PAYOUT"
blast:
	./scripts/blast.sh $(ARGS)
