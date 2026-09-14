CREATE TYPE task_type AS ENUM ('feature', 'evolution', 'bug', 'hotfix', 'chore');
CREATE TYPE task_status AS ENUM ('backlog', 'ready', 'in_progress', 'in_review', 'merged', 'to_deploy', 'done', 'closed');
CREATE TYPE task_priority AS ENUM ('critical', 'high', 'medium', 'low');

CREATE TABLE tasks
(
    id           SERIAL PRIMARY KEY,
    title        VARCHAR(50)   NOT NULL,
    description  VARCHAR(255)  NOT NULL,
    type         task_type     NOT NULL,
    status       task_status   NOT NULL,
    priority     task_priority NOT NULL,
    assignee     VARCHAR(50),
    created_date timestamp     NOT NULL,
    updated_date timestamp
);

COMMENT
ON COLUMN tasks.title IS 'Title of the task';
COMMENT
ON COLUMN tasks.description IS 'description of the task';
COMMENT
ON COLUMN tasks.type IS 'type of the task';
COMMENT
ON COLUMN tasks.status IS 'status of the task';
COMMENT
ON COLUMN tasks.priority IS 'priority of the task';
COMMENT
ON COLUMN tasks.assignee IS 'assignee of the task';