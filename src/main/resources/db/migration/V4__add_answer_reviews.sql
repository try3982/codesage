CREATE TABLE IF NOT EXISTS answer_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id UUID NOT NULL REFERENCES interview_questions(id) ON DELETE CASCADE,
    user_answer TEXT,
    score INT,
    score_label VARCHAR(200),
    improvements TEXT,
    best_answer TEXT,
    why_best TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_answer_reviews_question_id ON answer_reviews(question_id);
