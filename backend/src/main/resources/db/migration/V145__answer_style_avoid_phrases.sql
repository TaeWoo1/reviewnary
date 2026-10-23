-- 「되도록 피할 표현 → 대신 쓸 표현」(2026-10-23 오너 시안 승인). 「사용하지 않을 표현」(forbidden_phrases)은 그대로
-- 절대 금지로 남고, 이 열은 말투 선호다: 들어가도 초안은 저장되고 판매자가 그대로 두고 승인할 수 있다.
-- 한 줄에 하나, 표현과 대신 쓸 표현을 탭 하나로 나눈다(대신 쓸 표현은 비어 있을 수 있다). 탭과 줄바꿈은 입력에서 거절한다.
alter table organization_answer_style add column avoid_phrases text;
