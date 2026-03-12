CREATE TABLE IF NOT EXISTS interview_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    type VARCHAR(20),
    difficulty INT NOT NULL DEFAULT 3,
    question TEXT NOT NULL,
    focus VARCHAR(200),
    model_answer TEXT,
    why_best TEXT,
    user_answer TEXT,
    answer_review TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_interview_questions_job_id ON interview_questions(job_id);
CREATE INDEX IF NOT EXISTS idx_interview_questions_job_role ON interview_questions(job_id, role);
