-- Per-query execution stats for finding slow queries. The prod db container preloads the library
-- (shared_preload_libraries in deploy/docker-compose.prod.yml); without it the extension still installs,
-- but its view errors until the server is started with the library loaded.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
