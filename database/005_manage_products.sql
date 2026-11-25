USE osl_engine;

ALTER TABLE products
    ADD COLUMN category VARCHAR(80) NULL,
    ADD COLUMN selling_price DECIMAL(13,2) NULL,
    ADD COLUMN archived_at TIMESTAMP(6) NULL,
    ADD CONSTRAINT chk_catalog_selling_price CHECK (selling_price IS NULL OR selling_price > 0);

UPDATE products p
JOIN wholesaler_listings l ON l.product_id = p.product_id
SET p.category = l.category, p.selling_price = l.rate
WHERE p.category IS NULL AND p.selling_price IS NULL;
