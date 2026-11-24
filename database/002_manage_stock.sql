USE osl_engine;

CREATE TABLE IF NOT EXISTS products (
    product_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_id BIGINT NOT NULL,
    product_name VARCHAR(120) NOT NULL,
    unit ENUM('PIECE', 'CARTON', 'KILOGRAM') NOT NULL,
    requires_expiry BOOLEAN NOT NULL,
    CONSTRAINT fk_product_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT uq_product_name_unit UNIQUE (business_id, product_name, unit)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stock_lots (
    lot_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(15,3) NOT NULL,
    unit_cost DECIMAL(13,2) NOT NULL,
    expiry_date DATE,
    source_name VARCHAR(120) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_lot_product FOREIGN KEY (product_id) REFERENCES products(product_id),
    CONSTRAINT chk_lot_quantity CHECK (quantity >= 0),
    CONSTRAINT chk_lot_cost CHECK (unit_cost >= 0),
    INDEX ix_lot_product_receipt (product_id, lot_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stock_movements (
    movement_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    quantity_delta DECIMAL(15,3) NOT NULL,
    reason VARCHAR(120) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_movement_lot FOREIGN KEY (lot_id) REFERENCES stock_lots(lot_id),
    CONSTRAINT chk_movement_quantity CHECK (quantity_delta <> 0)
) ENGINE=InnoDB;
