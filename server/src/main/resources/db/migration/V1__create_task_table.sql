CREATE TABLE task
(
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(50)  NOT NULL,
    description  VARCHAR(255) NOT NULL,
    type         VARCHAR(10)  NOT NULL CHECK (type IN ('feature', 'evolution', 'bug', 'hotfix', 'chore')),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('backlog', 'ready', 'in_progress', 'in_review', 'merged', 'to_deploy', 'done', 'closed')),
    priority     VARCHAR(10)  NOT NULL CHECK (priority IN ('critical', 'high', 'medium', 'low')),
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
