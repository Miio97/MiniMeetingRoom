-- Run manually on Railway only AFTER deploying the server that retires NETWORK_STATS persistence.
-- Destructive: drops all connection-statistics history. Other business tables are not changed.
-- Optional backup/rollback instructions: docs/client-stats-migration.md
DROP TABLE IF EXISTS Thong_Ke_Ket_Noi;
