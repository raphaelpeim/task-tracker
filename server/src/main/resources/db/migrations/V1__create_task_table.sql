CREATE TABLE tasks
(
    id           SERIAL PRIMARY KEY,
    title        VARCHAR(50)   NOT NULL,
    description  VARCHAR(255)  NOT NULL,
    type         VARCHAR(10)  NOT NULL CHECK (type IN ('feature', 'evolution', 'bug', 'hotfix', 'chore')),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('backlog', 'ready', 'in_progress', 'in_review', 'merged', 'to_deploy', 'done', 'closed')),
    priority     VARCHAR(10)  NOT NULL CHECK (priority IN ('critical', 'high', 'medium', 'low')),
    assignee     VARCHAR(50),
    created_date timestamp     NOT NULL,
    updated_date timestamp
);

COMMENT
ON COLUMN tasks.title IS 'Title of the task';
COMMENT
ON COLUMN tasks.description IS 'Description of the task';
COMMENT
ON COLUMN tasks.type IS 'Type of the task';
COMMENT
ON COLUMN tasks.status IS 'Status of the task';
COMMENT
ON COLUMN tasks.priority IS 'Priority of the task';
COMMENT
ON COLUMN tasks.assignee IS 'Assignee of the task';
