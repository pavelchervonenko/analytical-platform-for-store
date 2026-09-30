-- A transaction contributes one semantic source revision per affected store.
-- The event is deferred so bulk sync does not lock the revision row per item.
CREATE TABLE store_analytics_source_state (
    store_id uuid PRIMARY KEY REFERENCES stores(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE store_analytics_source_events (
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    transaction_id bigint NOT NULL,
    PRIMARY KEY (store_id, transaction_id)
);

CREATE FUNCTION mark_store_analytics_source_changed(changed_store uuid)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    -- A store delete can cascade assignment rows; there is no future snapshot to invalidate.
    IF changed_store IS NOT NULL AND EXISTS (SELECT 1 FROM stores WHERE id = changed_store) THEN
        INSERT INTO store_analytics_source_events (store_id, transaction_id)
        VALUES (changed_store, txid_current())
        ON CONFLICT DO NOTHING;
    END IF;
END;
$$;

CREATE FUNCTION commit_store_analytics_source_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Serializes multi-store event flushes without holding a global lock during sync.
    PERFORM pg_advisory_xact_lock(7817439123001);
    IF NOT EXISTS (SELECT 1 FROM stores WHERE id = NEW.store_id) THEN
        RETURN NULL;
    END IF;
    INSERT INTO store_analytics_source_state (store_id, revision)
    VALUES (NEW.store_id, 1)
    ON CONFLICT (store_id) DO UPDATE
        SET revision = store_analytics_source_state.revision + 1,
            changed_at = clock_timestamp();
    DELETE FROM store_analytics_source_events
    WHERE store_id = NEW.store_id AND transaction_id = NEW.transaction_id;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER store_analytics_source_event_committed
    AFTER INSERT ON store_analytics_source_events
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION commit_store_analytics_source_change();

CREATE FUNCTION mark_seller_analytics_source_row()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_row jsonb;
    new_row jsonb;
    changed_store uuid;
    changed_connection uuid;
    changed_employee uuid;
    changed_product uuid;
    changed_category uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_row := to_jsonb(OLD); END IF;
    IF TG_OP <> 'DELETE' THEN new_row := to_jsonb(NEW); END IF;
    IF TG_OP = 'UPDATE' THEN
        IF TG_TABLE_NAME = 'employees' THEN
            IF (old_row -> 'is_active', old_row -> 'external_id', old_row -> 'connection_id')
                IS NOT DISTINCT FROM
               (new_row -> 'is_active', new_row -> 'external_id', new_row -> 'connection_id') THEN
                RETURN NULL;
            END IF;
        ELSIF TG_TABLE_NAME = 'stores' THEN
            IF (old_row -> 'timezone', old_row -> 'business_day_start', old_row -> 'is_active',
                old_row -> 'connection_id')
                IS NOT DISTINCT FROM
               (new_row -> 'timezone', new_row -> 'business_day_start', new_row -> 'is_active',
                new_row -> 'connection_id') THEN
                RETURN NULL;
            END IF;
        ELSIF TG_TABLE_NAME = 'sync_runs' THEN
            IF (old_row -> 'status', old_row -> 'sync_scope', old_row -> 'period_start',
                old_row -> 'period_end', old_row -> 'finished_at', old_row -> 'store_id',
                old_row -> 'connection_id', old_row -> 'sync_job_id')
                IS NOT DISTINCT FROM
               (new_row -> 'status', new_row -> 'sync_scope', new_row -> 'period_start',
                new_row -> 'period_end', new_row -> 'finished_at', new_row -> 'store_id',
                new_row -> 'connection_id', new_row -> 'sync_job_id') THEN
                RETURN NULL;
            END IF;
        ELSIF TG_TABLE_NAME = 'sync_jobs' THEN
            IF (old_row -> 'status', old_row -> 'phase', old_row -> 'period_start',
                old_row -> 'period_end', old_row -> 'cursor_start', old_row -> 'current_window_end',
                old_row -> 'connection_id', old_row -> 'finished_at')
                IS NOT DISTINCT FROM
               (new_row -> 'status', new_row -> 'phase', new_row -> 'period_start',
                new_row -> 'period_end', new_row -> 'cursor_start', new_row -> 'current_window_end',
                new_row -> 'connection_id', new_row -> 'finished_at') THEN
                RETURN NULL;
            END IF;
        ELSIF (old_row - 'updated_at' - 'version' - 'created_at')
            IS NOT DISTINCT FROM (new_row - 'updated_at' - 'version' - 'created_at') THEN
            RETURN NULL;
        END IF;
    END IF;

    IF TG_TABLE_NAME IN ('sales_documents', 'employee_store_assignments',
                         'employee_work_shifts', 'data_quality_issues',
                         'case_attach_decisions') THEN
        FOR changed_store IN
            SELECT DISTINCT value FROM (
                VALUES ((old_row ->> 'store_id')::uuid), ((new_row ->> 'store_id')::uuid)
            ) AS affected(value) WHERE value IS NOT NULL ORDER BY value
        LOOP
            PERFORM mark_store_analytics_source_changed(changed_store);
        END LOOP;
    ELSIF TG_TABLE_NAME = 'stores' THEN
        PERFORM mark_store_analytics_source_changed((new_row ->> 'id')::uuid);
    ELSIF TG_TABLE_NAME = 'sales_document_items' THEN
        FOR changed_store IN
            SELECT DISTINCT document.store_id FROM sales_documents document
            WHERE document.id IN ((old_row ->> 'sales_document_id')::uuid,
                                  (new_row ->> 'sales_document_id')::uuid)
            ORDER BY document.store_id
        LOOP
            PERFORM mark_store_analytics_source_changed(changed_store);
        END LOOP;
    ELSIF TG_TABLE_NAME = 'employees' THEN
        changed_employee := COALESCE((new_row ->> 'id')::uuid, (old_row ->> 'id')::uuid);
        FOR changed_store IN
            SELECT DISTINCT assignment.store_id FROM employee_store_assignments assignment
            WHERE assignment.employee_id = changed_employee ORDER BY assignment.store_id
        LOOP
            PERFORM mark_store_analytics_source_changed(changed_store);
        END LOOP;
    ELSIF TG_TABLE_NAME IN ('sync_runs', 'sync_jobs') THEN
        IF TG_TABLE_NAME = 'sync_runs' THEN
            FOR changed_store IN
                SELECT DISTINCT value FROM (
                    VALUES ((old_row ->> 'store_id')::uuid), ((new_row ->> 'store_id')::uuid)
                ) AS affected(value) WHERE value IS NOT NULL ORDER BY value
            LOOP
                PERFORM mark_store_analytics_source_changed(changed_store);
            END LOOP;
        END IF;
        FOR changed_connection IN
            SELECT DISTINCT value FROM (
                VALUES ((old_row ->> 'connection_id')::uuid),
                       ((new_row ->> 'connection_id')::uuid)
            ) AS connections(value) WHERE value IS NOT NULL ORDER BY value
        LOOP
            FOR changed_store IN
                SELECT id FROM stores WHERE connection_id = changed_connection ORDER BY id
            LOOP
                PERFORM mark_store_analytics_source_changed(changed_store);
            END LOOP;
        END LOOP;
    ELSIF TG_TABLE_NAME = 'analytics_categories' THEN
        changed_category := COALESCE((new_row ->> 'id')::uuid, (old_row ->> 'id')::uuid);
        FOR changed_store IN
            SELECT DISTINCT document.store_id
            FROM sales_document_items item
            JOIN sales_documents document ON document.id = item.sales_document_id
            WHERE item.analytics_category_id = changed_category ORDER BY document.store_id
        LOOP
            PERFORM mark_store_analytics_source_changed(changed_store);
        END LOOP;
    ELSIF TG_TABLE_NAME = 'product_category_assignments' THEN
        FOR changed_product IN
            SELECT DISTINCT value FROM (
                VALUES ((old_row ->> 'product_id')::uuid), ((new_row ->> 'product_id')::uuid)
            ) AS products(value) WHERE value IS NOT NULL
        LOOP
            FOR changed_store IN
                SELECT DISTINCT document.store_id FROM sales_document_items item
                JOIN sales_documents document ON document.id = item.sales_document_id
                WHERE item.product_id = changed_product ORDER BY document.store_id
            LOOP
                PERFORM mark_store_analytics_source_changed(changed_store);
            END LOOP;
        END LOOP;
    ELSIF TG_TABLE_NAME = 'warranty_attach_decisions' THEN
        SELECT document.store_id INTO changed_store FROM sales_document_items item
        JOIN sales_documents document ON document.id = item.sales_document_id
        WHERE item.id = (new_row ->> 'source_item_id')::uuid;
        PERFORM mark_store_analytics_source_changed(changed_store);
    ELSIF TG_TABLE_NAME = 'warranty_attach_allocations' THEN
        SELECT store_id INTO changed_store FROM sales_documents
        WHERE id = (new_row ->> 'device_document_id')::uuid;
        PERFORM mark_store_analytics_source_changed(changed_store);
        SELECT document.store_id INTO changed_store FROM warranty_attach_decisions decision
        JOIN sales_document_items item ON item.id = decision.source_item_id
        JOIN sales_documents document ON document.id = item.sales_document_id
        WHERE decision.id = (new_row ->> 'decision_id')::uuid;
        PERFORM mark_store_analytics_source_changed(changed_store);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER seller_source_sales_documents AFTER INSERT OR UPDATE OR DELETE ON sales_documents
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_sales_items AFTER INSERT OR UPDATE OR DELETE ON sales_document_items
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_assignments AFTER INSERT OR UPDATE OR DELETE ON employee_store_assignments
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_employees AFTER UPDATE ON employees
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_shifts AFTER INSERT OR UPDATE OR DELETE ON employee_work_shifts
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_categories AFTER UPDATE ON analytics_categories
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_product_categories AFTER INSERT OR UPDATE OR DELETE ON product_category_assignments
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_warranty_decisions AFTER INSERT ON warranty_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_warranty_allocations AFTER INSERT ON warranty_attach_allocations
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_case_decisions AFTER INSERT ON case_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_sync_runs AFTER INSERT OR UPDATE OR DELETE ON sync_runs
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_sync_jobs AFTER INSERT OR UPDATE OR DELETE ON sync_jobs
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_stores AFTER UPDATE ON stores
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
CREATE TRIGGER seller_source_quality AFTER INSERT OR UPDATE OR DELETE ON data_quality_issues
    FOR EACH ROW EXECUTE FUNCTION mark_seller_analytics_source_row();
