-- 판매자 PC가 공급자 서버에 등록된 기록 — docs/vendor_server_contract_v1.md §2.
--
-- 판매자 PC 설치에서만 쓰이며 한 행 이하다(VendorLinkService가 교체한다). 설치 토큰과 설치 X25519 개인키는
-- 둘 다 vault 키로 봉인된 봉투(EnvelopeCipher)로만 저장한다 — 평문 칸은 없다. 공개키는 서버에 등록했고 개인키에서
-- 다시 계산되므로 따로 두지 않는다.
create table vendor_install_link (
    id                 uuid primary key,
    install_id         uuid         not null,
    device_name        varchar(80)  not null,
    token_enc          bytea        not null,
    private_key_enc    bytea        not null,
    encryption_key_id  varchar(100) not null,
    token_expires_at   timestamptz  not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null
);
