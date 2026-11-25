USE osl_engine;

CREATE TABLE IF NOT EXISTS sales (
    sale_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_id BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sale_type ENUM('RETAIL', 'WHOLESALE') NOT NULL,
    buyer_name VARCHAR(120),
    sold_at DATETIME NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    payment_type VARCHAR(16) NOT NULL DEFAULT 'CASH',
    revenue DECIMAL(30,2) NOT NULL,
    total_cost DECIMAL(30,2) NOT NULL,
    gross_profit DECIMAL(30,2) NOT NULL,
    CONSTRAINT fk_sale_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT uq_sale_request UNIQUE (business_id, request_key),
    CONSTRAINT chk_sale_buyer CHECK (
        (sale_type = 'RETAIL' AND buyer_name IS NULL) OR
        (sale_type = 'WHOLESALE' AND buyer_name IS NOT NULL)
    ),
    CONSTRAINT chk_sale_totals CHECK (
        revenue > 0 AND total_cost >= 0 AND gross_profit = revenue - total_cost
    ),
    INDEX ix_sale_business_time (business_id, sold_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sale_items (
    sale_id BIGINT NOT NULL,
    lot_id BIGINT NOT NULL,
    product_name VARCHAR(120) NOT NULL,
    unit ENUM('PIECE', 'CARTON', 'KILOGRAM') NOT NULL,
    quantity DECIMAL(15,3) NOT NULL,
    unit_selling_price DECIMAL(13,2),
    unit_cost DECIMAL(13,2) NOT NULL,
    line_total DECIMAL(30,2) NOT NULL,
    line_cost DECIMAL(30,2) NOT NULL,
    PRIMARY KEY (sale_id, lot_id),
    CONSTRAINT fk_item_sale FOREIGN KEY (sale_id) REFERENCES sales(sale_id),
    CONSTRAINT fk_item_lot FOREIGN KEY (lot_id) REFERENCES stock_lots(lot_id),
    CONSTRAINT chk_sale_item_amounts CHECK (
        quantity > 0 AND unit_cost >= 0 AND line_total >= 0 AND line_cost >= 0
        AND (unit_selling_price IS NULL OR unit_selling_price > 0)
    )
) ENGINE=InnoDB;
