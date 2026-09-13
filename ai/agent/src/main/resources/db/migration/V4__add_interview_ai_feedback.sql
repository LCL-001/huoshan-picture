alter table interview_turn
    add column question_context text null after question,
    add column feedback_detail mediumtext null after feedback,
    add column evidence_detail mediumtext null after feedback_detail;
