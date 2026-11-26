USE osl_engine;

ALTER TABLE wholesaler_listing_reports
    MODIFY reason ENUM('MISLEADING_DETAILS', 'INCORRECT_RATE', 'INAPPROPRIATE_CONTENT', 'OTHER', 'DANGEROUS_PRODUCT') NOT NULL;

ALTER TABLE wholesaler_report_reviews
    DROP CHECK chk_content_review_note,
    MODIFY outcome ENUM('CLEARED', 'HIDDEN', 'SUSPENDED') NOT NULL,
    ADD CONSTRAINT chk_content_review_note CHECK (outcome = 'CLEARED' OR CHAR_LENGTH(TRIM(review_note)) > 0);