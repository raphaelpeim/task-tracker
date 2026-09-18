CREATE TABLE task
(
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(50)  NOT NULL,
    description  VARCHAR(255) NOT NULL,
    type         VARCHAR(10)  NOT NULL CHECK (type IN ('FEATURE', 'EVOLUTION', 'BUG', 'HOTFIX', 'CHORE')),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW', 'MERGED', 'TO_DEPLOY', 'DONE', 'CLOSED')),
    priority     VARCHAR(10)  NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    assignee     VARCHAR(50),
    created_date timestamp    NOT NULL,
    updated_date timestamp
);

COMMENT
ON COLUMN task.title IS 'Title of the task';
COMMENT
ON COLUMN task.description IS 'Description of the task';
COMMENT
ON COLUMN task.type IS 'Type of the task';
COMMENT
ON COLUMN task.status IS 'Status of the task';
COMMENT
ON COLUMN task.priority IS 'Priority of the task';
COMMENT
ON COLUMN task.assignee IS 'Assignee of the task';
