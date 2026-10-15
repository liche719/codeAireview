CREATE TABLE IF NOT EXISTS agent_run (
    id BIGSERIAL PRIMARY KEY,
    command_task_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    model_turn_count INT NOT NULL DEFAULT 0,
    tool_call_count INT NOT NULL DEFAULT 0,
    continuation_count INT NOT NULL DEFAULT 0,
    waiting_workflow_type VARCHAR(32),
    waiting_workflow_task_id BIGINT,
    continuation_context TEXT,
    deadline_at TIMESTAMP,
    last_response TEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_agent_run_command_task
        FOREIGN KEY (command_task_id) REFERENCES pr_command_task (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_run_command_task UNIQUE (command_task_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_waiting
    ON agent_run (status, updated_at)
    WHERE status = 'WAITING_WORKFLOW';

CREATE TABLE IF NOT EXISTS agent_step (
    id BIGSERIAL PRIMARY KEY,
    agent_run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_summary TEXT,
    output_summary TEXT,
    duration_millis BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_step_run
        FOREIGN KEY (agent_run_id) REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_step_sequence UNIQUE (agent_run_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_agent_step_run
    ON agent_step (agent_run_id, sequence_no);
