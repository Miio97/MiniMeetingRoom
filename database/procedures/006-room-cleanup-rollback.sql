-- Stop/drain the new Java server first. This does not restore deleted bytes or content.
DROP PROCEDURE IF EXISTS mmr_cleanup_commit_v1;
DROP PROCEDURE IF EXISTS mmr_cleanup_batch_v1;
DROP PROCEDURE IF EXISTS mmr_cleanup_fence_v1;
