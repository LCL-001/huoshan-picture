create table if not exists knowledge_document
(
    id            bigint unsigned auto_increment primary key,
    user_id       varchar(64) not null,
    name          varchar(255) not null,
    document_type varchar(32) not null,
    storage_path  varchar(512) not null,
    content_text  mediumtext,
    status        varchar(32) not null,
    error_message varchar(500),
    chunk_count   int not null default 0,
    create_time   datetime not null default current_timestamp,
    update_time   datetime not null default current_timestamp on update current_timestamp,
    is_delete     tinyint(1) not null default 0,
    index idx_knowledge_document_user_status (user_id, status)
) comment '用户学习资料';

create table if not exists knowledge_chunk
(
    id                 bigint unsigned auto_increment primary key,
    document_id        bigint unsigned not null,
    user_id            varchar(64) not null,
    chunk_index        int not null,
    content            text not null,
    vector_document_id varchar(64) not null,
    create_time        datetime not null default current_timestamp,
    unique key uk_knowledge_chunk_document_index (document_id, chunk_index),
    index idx_knowledge_chunk_user_document (user_id, document_id)
) comment '学习资料分块索引';

create table if not exists gap_analysis
(
    id          varchar(64) primary key,
    user_id     varchar(64) not null,
    status      varchar(32) not null,
    content     mediumtext,
    create_time datetime not null default current_timestamp,
    update_time datetime not null default current_timestamp on update current_timestamp,
    index idx_gap_analysis_user_time (user_id, create_time)
) comment '能力差距分析';

create table if not exists study_plan
(
    id              varchar(64) primary key,
    user_id         varchar(64) not null,
    gap_analysis_id varchar(64),
    title           varchar(200) not null,
    status          varchar(32) not null,
    confirm_token   varchar(64) not null,
    create_time     datetime not null default current_timestamp,
    update_time     datetime not null default current_timestamp on update current_timestamp,
    unique key uk_study_plan_confirm_token (confirm_token),
    index idx_study_plan_user_status (user_id, status)
) comment '学习计划';

create table if not exists study_task
(
    id             varchar(64) primary key,
    plan_id        varchar(64) not null,
    user_id        varchar(64) not null,
    day_number     int not null,
    title          varchar(200) not null,
    description    text,
    task_status    varchar(32) not null,
    scheduled_date date,
    create_time    datetime not null default current_timestamp,
    update_time    datetime not null default current_timestamp on update current_timestamp,
    index idx_study_task_plan_day (plan_id, day_number),
    index idx_study_task_user_status (user_id, task_status)
) comment '学习任务';

create table if not exists interview_session
(
    id             varchar(64) primary key,
    user_id        varchar(64) not null,
    status         varchar(32) not null,
    topic          varchar(100) not null,
    question_limit int not null,
    create_time    datetime not null default current_timestamp,
    update_time    datetime not null default current_timestamp on update current_timestamp,
    index idx_interview_session_user_status (user_id, status)
) comment '模拟面试会话';

create table if not exists interview_turn
(
    id              varchar(64) primary key,
    session_id      varchar(64) not null,
    turn_number     int not null,
    question        text not null,
    answer          text,
    feedback        mediumtext,
    score           int,
    turn_status     varchar(32) not null,
    create_time     datetime not null default current_timestamp,
    update_time     datetime not null default current_timestamp on update current_timestamp,
    unique key uk_interview_turn_session_number (session_id, turn_number)
) comment '模拟面试轮次';

create table if not exists agent_run
(
    id              varchar(64) primary key,
    user_id         varchar(64) not null,
    run_type        varchar(32) not null,
    status          varchar(32) not null,
    model_name      varchar(100),
    duration_ms     bigint,
    input_tokens    int,
    output_tokens   int,
    retrieval_count int not null default 0,
    error_code      varchar(64),
    error_message   varchar(500),
    create_time     datetime not null default current_timestamp,
    update_time     datetime not null default current_timestamp on update current_timestamp,
    index idx_agent_run_user_time (user_id, create_time)
) comment 'Agent运行审计';
