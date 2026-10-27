-- El relay con reparto justo elige, por trabajo, los pendientes de mayor prioridad (ROW_NUMBER por job_id).
CREATE INDEX idx_work_item_outbox_job ON work_item (job_id, priority DESC, created_at)
    WHERE status = 'PENDING' AND published_at IS NULL;
