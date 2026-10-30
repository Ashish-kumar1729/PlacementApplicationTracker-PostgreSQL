CREATE TABLE IF NOT EXISTS applications (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_name VARCHAR(150) NOT NULL CHECK (length(btrim(company_name)) > 0),
    role VARCHAR(150) NOT NULL CHECK (length(btrim(role)) > 0),
    application_date DATE NOT NULL,
    deadline DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
        CHECK (status IN ('APPLIED', 'ASSESSMENT', 'INTERVIEW', 'OFFERED', 'REJECTED')),
    CONSTRAINT deadline_not_before_application CHECK (deadline >= application_date)
);
CREATE UNIQUE INDEX IF NOT EXISTS applications_unique_company_role_date
    ON applications (lower(btrim(company_name)), lower(btrim(role)), application_date);
CREATE INDEX IF NOT EXISTS applications_deadline_idx ON applications (deadline);
CREATE INDEX IF NOT EXISTS applications_status_idx ON applications (status);
