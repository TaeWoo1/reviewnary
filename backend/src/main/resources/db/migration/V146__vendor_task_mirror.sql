-- 판매자 PC와 휴대폰이 같은 초안을 다룬다 — docs/vendor_server_contract_v1.md D7 개정(2026-10-25).
-- PC가 만든 초안은 서버에 자동으로 올라가고, 휴대폰에서 고친 문장은 PC 초안이 된다.
-- mirrored_server_version · mirrored_local_version: 마지막으로 맞춘 서버 버전과 이 PC의 초안 버전. 어느 쪽이 바뀌었는지 가른다.
-- set_aside_local_version: 두 기기에서 동시에 고쳤을 때 휴대폰 문장에 밀려난 이 PC의 초안 버전. 판매자가 고를 때까지 둔다.
alter table vendor_reply_task add column mirrored_server_version integer;
alter table vendor_reply_task add column mirrored_local_version integer;
alter table vendor_reply_task add column set_aside_local_version integer;
