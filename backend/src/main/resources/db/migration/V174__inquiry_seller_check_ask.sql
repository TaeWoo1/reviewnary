-- What the seller is asked to check on the seller centre for an inquiry (2026-11-02 seller-check review, fourth pass).
--
-- Read off the needs the last draft attempt left open: ORDER_ACTION (something to be done to this order) or
-- ORDER_STATE (how it stands). It is the system's reading, never a verified one, and it is kept so that a reload — and
-- later the Home list — asks for the same check the draft screen did. Null when no order need is open.
alter table inquiry_work_item add column if not exists seller_check_ask varchar(24);
