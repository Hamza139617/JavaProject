USE osl_engine;

CREATE TABLE IF NOT EXISTS wholesaler_account_status_history (
    change_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    wholesaler_id BIGINT NOT NULL,
    administrator_id BIGINT NOT NULL,
    previous_status ENUM('ACTIVE', 'SUSPENDED') NOT NULL,
    new_status ENUM('ACTIVE', 'SUSPENDED') NOT NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_status_history_wholesaler FOREIGN KEY (wholesaler_id) REFERENCES user_accounts(account_id),
    CONSTRAINT fk_status_history_administrator FOREIGN KEY (administrator_id) REFERENCES user_accounts(account_id),
    CONSTRAINT chk_status_history_transition CHECK (previous_status <> new_status),
    CONSTRAINT chk_status_history_reason CHECK (new_status <> 'SUSPENDED' OR CHAR_LENGTH(TRIM(reason)) > 0),
    INDEX ix_status_history_account (wholesaler_id, changed_at, change_id)
) ENGINE=InnoDB;

CREATE OR REPLACE ALGORITHM=MERGE SQL SECURITY DEFINER VIEW wholesaler_account_status AS
SELECT account_id, account_status
FROM user_accounts
WHERE role = 'WHOLESALER'
WITH CASCADED CHECK OPTION;