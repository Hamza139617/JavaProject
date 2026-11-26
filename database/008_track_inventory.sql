USE osl_engine;

CREATE TABLE IF NOT EXISTS inventory_locations (
    location_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_id BIGINT NOT NULL,
    storeroom VARCHAR(80) NOT NULL,
    rack VARCHAR(60) NOT NULL DEFAULT '',
    bin VARCHAR(60) NOT NULL DEFAULT '',
    slot_capacity INT NOT NULL,
    CONSTRAINT fk_inventory_location_business FOREIGN KEY (business_id) REFERENCES businesses(business_id),
    CONSTRAINT uq_inventory_location_path UNIQUE (business_id, storeroom, rack, bin),
    CONSTRAINT chk_inventory_location_capacity CHECK (slot_capacity BETWEEN 1 AND 999999)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS inventory_item_positions (
    product_id BIGINT NOT NULL PRIMARY KEY,
    location_id BIGINT NOT NULL,
    CONSTRAINT fk_inventory_item_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_item_location FOREIGN KEY (location_id) REFERENCES inventory_locations(location_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS inventory_stock_positions (
    lot_id BIGINT NOT NULL PRIMARY KEY,
    location_id BIGINT NOT NULL,
    CONSTRAINT fk_inventory_stock_lot FOREIGN KEY (lot_id) REFERENCES stock_lots(lot_id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_stock_location FOREIGN KEY (location_id) REFERENCES inventory_locations(location_id)
) ENGINE=InnoDB;