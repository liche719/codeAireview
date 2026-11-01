-- 2026-11-02：团队规范检索加"精确词通道"
--
-- 背景：向量召回对 @Transactional、ERR_502、类名方法名这类**精确 token** 天然弱——
-- 语义上"差不多"，但用户要的恰恰是"就是这一条"。
-- 做法：用 pg_trgm 给 content 建 GIN 索引，让 ILIKE 子串匹配走索引（不扫全表），
-- 再把关键词结果和向量结果用 RRF 融合（只看名次，抹平两路分数量纲差异）。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_rule_chunk_content_trgm
    ON rule_chunk USING gin (content gin_trgm_ops);
