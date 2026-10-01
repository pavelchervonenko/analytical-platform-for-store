-- Catalog role snapshots can change attach facts after the sale item was first synced.
CREATE FUNCTION mark_catalog_sale_role_source_changed()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    changed_store uuid;
BEGIN
    SELECT document.store_id INTO changed_store
    FROM sales_document_items item
    JOIN sales_documents document ON document.id = item.sales_document_id
    WHERE item.id = NEW.item_id;
    PERFORM mark_store_analytics_source_changed(changed_store);
    RETURN NULL;
END;
$$;

CREATE TRIGGER seller_source_catalog_sale_roles AFTER INSERT ON catalog_sale_role_snapshots
    FOR EACH ROW EXECUTE FUNCTION mark_catalog_sale_role_source_changed();

-- V86 changed the attach projection even when no row changed. Existing seller checkpoints
-- must be re-evaluated under that projection instead of appearing CURRENT by old identity.
SELECT mark_store_analytics_source_changed(id) FROM stores ORDER BY id;
