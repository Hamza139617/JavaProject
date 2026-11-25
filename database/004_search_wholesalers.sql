USE osl_engine;

CREATE TABLE IF NOT EXISTS wholesaler_profiles (
    business_id BIGINT NOT NULL PRIMARY KEY,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    city VARCHAR(100) NOT NULL DEFAULT '',
    public_address VARCHAR(200) NOT NULL DEFAULT '',
    description VARCHAR(1000) NOT NULL DEFAULT '',
    show_phone BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_directory_profile_business FOREIGN KEY (business_id) REFERENCES businesses(business_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wholesaler_listings (
    product_id BIGINT NOT NULL PRIMARY KEY,
    is_listed BOOLEAN NOT NULL DEFAULT TRUE,
    category VARCHAR(80) NOT NULL,
    rate DECIMAL(13,2) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_directory_listing_product FOREIGN KEY (product_id) REFERENCES products(product_id),
    CONSTRAINT chk_directory_listing_rate CHECK (rate > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wholesaler_listing_reports (
    report_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reporter_business_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    reason ENUM('MISLEADING_DETAILS', 'INCORRECT_RATE', 'INAPPROPRIATE_CONTENT', 'OTHER') NOT NULL,
    comments VARCHAR(500) NOT NULL DEFAULT '',
    report_status ENUM('PENDING', 'REVIEWED') NOT NULL DEFAULT 'PENDING',
    submitted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_directory_report_retailer FOREIGN KEY (reporter_business_id) REFERENCES businesses(business_id),
    CONSTRAINT fk_directory_report_listing FOREIGN KEY (product_id) REFERENCES wholesaler_listings(product_id),
    CONSTRAINT uq_directory_report UNIQUE (reporter_business_id, product_id),
    INDEX ix_directory_pending_reports (report_status, submitted_at)
) ENGINE=InnoDB;
