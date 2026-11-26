USE osl_engine;

CREATE TABLE IF NOT EXISTS customers (
    customer_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_id BIGINT NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    phone VARCHAR(13) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preferences VARCHAR(500) NOT NULL DEFAULT '',
    notes VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_customer_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT uq_customer_phone UNIQUE (business_id, phone),
    CONSTRAINT uq_customer_workspace_id UNIQUE (business_id, customer_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS customer_interactions (
    interaction_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    interaction_type ENUM('VISIT', 'FEEDBACK', 'OTHER') NOT NULL,
    interaction_on DATE NOT NULL,
    rating TINYINT,
    notes VARCHAR(1000) NOT NULL DEFAULT '',
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_interaction_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT chk_interaction_rating CHECK (
        (rating IS NULL OR (interaction_type = 'FEEDBACK' AND rating BETWEEN 1 AND 5))
        AND (interaction_type <> 'FEEDBACK' OR rating IS NOT NULL OR notes <> '')
    ),
    INDEX ix_customer_interaction_time (customer_id, interaction_on)
) ENGINE=InnoDB;

ALTER TABLE sales
    ADD COLUMN customer_id BIGINT NULL,
    ADD CONSTRAINT fk_sale_customer_workspace FOREIGN KEY (business_id, customer_id)
        REFERENCES customers(business_id, customer_id),
    ADD CONSTRAINT chk_sale_customer_retail CHECK (customer_id IS NULL OR sale_type = 'RETAIL'),
    ADD INDEX ix_sales_customer_time (business_id, customer_id, sold_at);
