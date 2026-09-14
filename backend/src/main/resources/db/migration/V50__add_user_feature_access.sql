CREATE TABLE user_feature_access (
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    feature text NOT NULL CHECK (feature IN ('PLAN', 'SHIFTS', 'PAYROLL')),
    granted_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, feature)
);

COMMENT ON TABLE user_feature_access IS
    'Global manager access to operational application sections across assigned stores.';
COMMENT ON COLUMN user_feature_access.feature IS
    'PLAN, SHIFTS or PAYROLL. Reports remain outside these operational permissions.';

INSERT INTO user_feature_access (user_id, feature)
SELECT app_users.id, available_features.feature
FROM app_users
CROSS JOIN (VALUES ('PLAN'), ('SHIFTS'), ('PAYROLL')) AS available_features(feature)
WHERE app_users.role = 'MANAGER';
