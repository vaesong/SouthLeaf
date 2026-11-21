-- Studio-chosen work order. scope "all" is the mini-program 全部 list.
-- Any other scope is one business id. Missing rows stay after the saved order.

CREATE TABLE public_work_display_order (
  scope VARCHAR(64) NOT NULL,
  work_id CHAR(36) NOT NULL,
  position INT NOT NULL,
  PRIMARY KEY (scope, work_id),
  KEY ix_public_work_display_order_rank (scope, position),
  CONSTRAINT ck_public_work_display_order_scope CHECK (
    scope REGEXP '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$'
  ),
  CONSTRAINT ck_public_work_display_order_position CHECK (position >= 0 AND position <= 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
