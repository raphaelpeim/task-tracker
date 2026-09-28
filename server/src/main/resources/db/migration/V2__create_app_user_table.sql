CREATE TABLE app_user
(
    id           BIGSERIAL PRIMARY KEY,
    firstname    VARCHAR(50)  NOT NULL,
    lastname     VARCHAR(50)  NOT NULL,
    username     VARCHAR(50)  NOT NULL UNIQUE,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    role         VARCHAR(50)  NOT NULL CHECK ( role IN ('ADMIN', 'USER', 'GUEST') ),
    created_date TIMESTAMP    NOT NULL,
    updated_date TIMESTAMP    NOT NULL
);

COMMENT
    ON COLUMN app_user.firstname IS 'Firstname of the user';
COMMENT
    ON COLUMN app_user.lastname IS 'Lastname of the user';
COMMENT
    ON COLUMN app_user.username IS 'Username of the user';
COMMENT
    ON COLUMN app_user.email IS 'Email of the user';
COMMENT
    ON COLUMN app_user.password IS 'Password of the user';
COMMENT
    ON COLUMN app_user.role IS 'Role of the user';
