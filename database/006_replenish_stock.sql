USE osl_engine;

CREATE TABLE IF NOT EXISTS replenishment_settings (
    business_id BIGINT NOT NULL PRIMARY KEY,
    low_threshold DECIMAL(15,3) NOT NULL DEFAULT 10,
    notices_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_hours INT NOT NULL DEFAULT 24,
    CONSTRAINT fk_replenishment_settings_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT chk_replenishment_threshold CHECK (low_threshold > 0),
    CONSTRAINT chk_replenishment_reminder CHECK (reminder_hours BETWEEN 1 AND 720)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS replenishment_capacity (
    product_id BIGINT NOT NULL PRIMARY KEY,
    maximum_quantity DECIMAL(15,3) NOT NULL,
    CONSTRAINT fk_replenishment_capacity_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT chk_replenishment_capacity CHECK (maximum_quantity > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS replenishment_plans (
    plan_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    replaced_lot_id BIGINT,
    source_name VARCHAR(120) NOT NULL,
    planned_quantity DECIMAL(15,3) NOT NULL,
    expected_on DATE NOT NULL,
    status ENUM('PLANNED', 'RECEIVED', 'CANCELLED') NOT NULL DEFAULT 'PLANNED',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    received_lot_id BIGINT UNIQUE,
    CONSTRAINT fk_replenishment_plan_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT fk_replenishment_plan_product FOREIGN KEY (product_id) REFERENCES products(product_id),
    CONSTRAINT fk_replenishment_previous_lot FOREIGN KEY (replaced_lot_id) REFERENCES stock_lots(lot_id),
    CONSTRAINT fk_replenishment_received_lot FOREIGN KEY (received_lot_id) REFERENCES stock_lots(lot_id),
    CONSTRAINT chk_replenishment_plan_qty CHECK (planned_quantity > 0),
    CONSTRAINT chk_replenishment_receipt CHECK ((status = 'RECEIVED') = (received_lot_id IS NOT NULL)),
    INDEX ix_replenishment_plan_lookup (business_id, product_id, replaced_lot_id, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS replenishment_notices (
    business_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    lot_key BIGINT NOT NULL,
    notified_at TIMESTAMP(6) NOT NULL,
    notice_count INT NOT NULL DEFAULT 1,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (business_id, product_id, lot_key),
    CONSTRAINT fk_replenishment_notice_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT fk_replenishment_notice_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE
) ENGINE=InnoDB;
