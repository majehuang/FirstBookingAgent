-- 分布式 Agent 服务的 PostgreSQL 表结构
--
-- 幂等：全部 IF NOT EXISTS，可以反复执行。
-- 语句之间用 ";" 分隔，不要在这里写函数体（$$ ... $$），迁移器是按分号切的。

-- ============================================================
-- 消息表：真相源
--
-- 历史拉取与超窗重放都读这张表。outbox（Redis Stream）只是它的
-- 短期缓存，保留 5–10 分钟，掉了可以从这里补回来。
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_message (
    session_id    varchar(128) NOT NULL,
    msg_seq       bigint       NOT NULL,
    user_id       varchar(128) NOT NULL,
    reply_id      varchar(64)  NOT NULL,
    block_id      varchar(128) NOT NULL,
    -- 谁说的。协议 v1.1 新增：用户自己的消息也落这张表，
    -- 客户端收到推送后才回显，一个会话里只有一套顺序来源
    msg_role      varchar(16)  NOT NULL DEFAULT 'ASSISTANT',
    msg_type      varchar(32)  NOT NULL,
    -- 用户消息的幂等键（客户端生成，重试时不变）。助手侧消息为 NULL
    instruction_id varchar(64),
    fallback_text text         NOT NULL,
    payload       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    superseded    boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL,
    -- 主键就是 (session, seq)：数据库本身来保证每个 session 内序号唯一。
    -- INV-10 要求单调且无洞，唯一性这一半在这里被强制，
    -- 单调那一半由 agent_msg_seq 的原子自增保证。
    PRIMARY KEY (session_id, msg_seq)
);

-- turn 认领标记。
--
-- 客户端重试（INV-1）会在 inbox 里留下同一个 instructionId 的第二条指令，
-- Worker 必须只跑一轮。用条件更新做原子认领：
--   UPDATE ... WHERE instruction_id = ? AND turn_claimed_at IS NULL
-- 受影响行数为 1 才是认领成功。放在 USER 消息那一行上，
-- 因为它本来就带 instruction_id 且每个指令唯一。
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS turn_claimed_at timestamptz;

-- 补齐 v1.1 新增的两列。CREATE TABLE IF NOT EXISTS 不会改动已存在的表。
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS msg_role varchar(16) NOT NULL DEFAULT 'ASSISTANT';
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS instruction_id varchar(64);

-- 幂等的落地点：同一个 instructionId 在一个 session 里只能有一条消息。
-- 客户端在超时或 5xx 时会带同一个 id 重试（INV-1），
-- 靠这个唯一索引让重复投递在数据库层面就写不进来，而不是依赖应用层记得去查。
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_message_instruction
    ON agent_message (session_id, instruction_id)
    WHERE instruction_id IS NOT NULL;

-- 加宽 block_id。
-- 上面的 CREATE TABLE IF NOT EXISTS 不会改动已存在的表，所以早期建的库要靠这条补齐。
-- varchar 加长在 PostgreSQL 里不触发表重写，反复执行也安全。
ALTER TABLE agent_message ALTER COLUMN block_id TYPE varchar(128);

-- 按 replyId 作废（turn 重跑）时用
CREATE INDEX IF NOT EXISTS idx_agent_message_reply
    ON agent_message (session_id, reply_id);

-- 历史拉取 GET .../messages?since= 的主查询路径。
-- 带 superseded 是为了让"过滤掉被重跑作废的 replyId"也走索引
CREATE INDEX IF NOT EXISTS idx_agent_message_fetch
    ON agent_message (session_id, msg_seq)
    WHERE superseded = false;

-- ============================================================
-- 每 session 的序号分配器
--
-- 不能用全局自增：多 session 交错会让每个 session 看到的序号是断的，
-- 客户端的空窗判定立刻失效（INV-10）。
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_msg_seq (
    session_id varchar(128) PRIMARY KEY,
    last_seq   bigint      NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- ============================================================
-- AgentScope 的 AgentStateStore
--
-- 上游只提供 InMemory 与 JsonFile 两个实现，多 pod 下都不能用。
-- version 列是为了 CAS：两个 pod 同时接管同一 session 时，
-- 后写的那个必须失败重试，而不是覆盖。
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_state (
    user_id    varchar(128) NOT NULL,
    session_id varchar(128) NOT NULL,
    state_key  varchar(256) NOT NULL,
    payload    jsonb        NOT NULL,
    is_list    boolean      NOT NULL DEFAULT false,
    version    bigint       NOT NULL DEFAULT 0,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, session_id, state_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_state_session
    ON agent_state (user_id, session_id);

-- ============================================================
-- AgentScope 的 BaseStore（workspace 方案 B）
--
-- HarnessAgent 默认开双层长期记忆，会往 workspace 写 MEMORY.md 与
-- memory/YYYY-MM-DD.md。多 pod 下用本地文件系统会让记忆按 pod 分叉，
-- 所以走这张表，IsolationScope.USER。
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_store_item (
    namespace  text         NOT NULL,
    item_key   varchar(512) NOT NULL,
    value      jsonb        NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (namespace, item_key)
);

-- BaseStore.search(namespace, offset, limit) 的路径
CREATE INDEX IF NOT EXISTS idx_agent_store_item_ns
    ON agent_store_item (namespace, item_key);

-- ============================================================
-- 事件日志（冷存储）
--
-- 全量 AgentEvent，异步旁路写入，失败不影响 turn（INV-5 后半句）。
-- 只用于排查与分析，任何在线逻辑都不许读它。
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_event_log (
    id         bigserial    PRIMARY KEY,
    session_id varchar(128) NOT NULL,
    user_id    varchar(128) NOT NULL,
    reply_id   varchar(64),
    event_type varchar(64)  NOT NULL,
    payload    jsonb        NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_event_log_session
    ON agent_event_log (session_id, created_at);
