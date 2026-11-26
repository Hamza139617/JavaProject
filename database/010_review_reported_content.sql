USE osl_engine;

CREATE TABLE IF NOT EXISTS wholesaler_report_evidence (
    report_id BIGINT NOT NULL PRIMARY KEY,
    product_name VARCHAR(120) NOT NULL,
    category VARCHAR(80) NOT NULL,
    rate DECIMAL(13,2) NOT NULL,
    CONSTRAINT fk_report_evidence FOREIGN KEY (report_id) REFERENCES wholesaler_listing_reports(report_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wholesaler_report_reviews (
    report_id BIGINT NOT NULL PRIMARY KEY,
    administrator_id BIGINT NOT NULL,
    outcome ENUM('CLEARED', 'HIDDEN') NOT NULL,
    review_note VARCHAR(500) NOT NULL DEFAULT '',
    reviewed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    seen_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_content_review_report FOREIGN KEY (report_id) REFERENCES wholesaler_listing_reports(report_id),
    CONSTRAINT fk_content_review_admin FOREIGN KEY (administrator_id) REFERENCES user_accounts(account_id),
    CONSTRAINT chk_content_review_note CHECK (outcome <> 'HIDDEN' OR CHAR_LENGTH(TRIM(review_note)) > 0)
) ENGINE=InnoDB;