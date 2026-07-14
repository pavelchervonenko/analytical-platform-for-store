CREATE TABLE employees (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    external_id text,
    full_name text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_employees_store_external_id
    ON employees (store_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX ix_employees_store_active ON employees (store_id, is_active);

CREATE TABLE product_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    external_id text,
    parent_id uuid REFERENCES product_categories(id),
    name text NOT NULL,
    analytics_type text CHECK (analytics_type IN ('DEVICE_NEW', 'DEVICE_USED', 'ACCESSORY', 'SERVICE', 'WARRANTY', 'OTHER')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_product_categories_store_external_id
    ON product_categories (store_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX ix_product_categories_store_type ON product_categories (store_id, analytics_type);

CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    external_id text,
    category_id uuid REFERENCES product_categories(id),
    sku text,
    name text NOT NULL,
    product_kind text NOT NULL DEFAULT 'UNKNOWN' CHECK (product_kind IN ('DEVICE', 'ACCESSORY', 'SERVICE', 'WARRANTY', 'OTHER', 'UNKNOWN')),
    is_active boolean NOT NULL DEFAULT true,
    cost_amount numeric(14, 2),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_products_store_external_id
    ON products (store_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX ix_products_store_category ON products (store_id, category_id);
CREATE INDEX ix_products_store_kind ON products (store_id, product_kind);

CREATE TABLE sales_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    external_id text NOT NULL,
    document_number text,
    document_type text NOT NULL,
    status text,
    sold_at timestamptz NOT NULL,
    employee_id uuid REFERENCES employees(id),
    customer_external_id text,
    gross_amount numeric(14, 2) NOT NULL DEFAULT 0,
    discount_amount numeric(14, 2) NOT NULL DEFAULT 0,
    cost_amount numeric(14, 2),
    profit_amount numeric(14, 2),
    margin_percent numeric(7, 4),
    is_return boolean NOT NULL DEFAULT false,
    source_updated_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_sales_documents_store_external_id ON sales_documents (store_id, external_id);
CREATE INDEX ix_sales_documents_store_sold_at ON sales_documents (store_id, sold_at DESC);
CREATE INDEX ix_sales_documents_employee_sold_at ON sales_documents (employee_id, sold_at DESC);
CREATE INDEX ix_sales_documents_store_type_status ON sales_documents (store_id, document_type, status);

CREATE TABLE sales_document_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_document_id uuid NOT NULL REFERENCES sales_documents(id) ON DELETE CASCADE,
    store_id uuid NOT NULL REFERENCES stores(id),
    external_id text,
    product_id uuid REFERENCES products(id),
    product_name text NOT NULL,
    category_id uuid REFERENCES product_categories(id),
    quantity numeric(14, 3) NOT NULL DEFAULT 1,
    unit_price numeric(14, 2) NOT NULL DEFAULT 0,
    gross_amount numeric(14, 2) NOT NULL DEFAULT 0,
    discount_amount numeric(14, 2) NOT NULL DEFAULT 0,
    cost_amount numeric(14, 2),
    profit_amount numeric(14, 2),
    analytics_type text CHECK (analytics_type IN ('DEVICE_NEW', 'DEVICE_USED', 'ACCESSORY', 'SERVICE', 'WARRANTY', 'OTHER')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_sales_document_items_external_id
    ON sales_document_items (sales_document_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX ix_sales_document_items_store_category ON sales_document_items (store_id, category_id);
CREATE INDEX ix_sales_document_items_store_type ON sales_document_items (store_id, analytics_type);
