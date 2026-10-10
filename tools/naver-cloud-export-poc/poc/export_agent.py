"""The browser-use agent that performs the official export.

There is no selector in this file, and there is none anywhere else in `poc/`. That is
the POC's second success criterion ("selector 기반 scraper 없음") and `run.py selfcheck`
asserts it by scanning this package's own source.

The agent is fenced three ways before it is handed the task:
  - `allowed_domains`  — it cannot leave the NAVER seller center (nid.naver.com is allowed
                         ONLY so that an auth screen can be RECOGNISED and reported).
  - `exclude_actions`  — no page JS evaluation, no web search, no local file access.
  - `output_model_schema` — the run ends in a typed verdict, not prose we would re-parse.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class ExportOutcome(BaseModel):
    """What the run is allowed to tell us. Note what is absent: no URL, no selector,
    no customer text, no file path, no cookie."""

    verdict: str = Field(
        description=(
            "Exactly one of: EXPORT_REQUESTED (the official Excel download control was "
            "activated), AUTH_REQUIRED (a login / re-verification / CAPTCHA screen was "
            "reached — you STOPPED), STORE_UNRESOLVED (could not read a stable store "
            "identifier), EXPORT_CONTROL_NOT_FOUND, UNSUPPORTED_STATE."
        )
    )
    observed_store_identifier: str = Field(
        default="",
        description=(
            "The stable, machine-readable store/account identifier shown by the seller "
            "center (e.g. the channel's own store or account number). Empty if none was "
            "readable. Do NOT put the store's display name here."
        ),
    )
    observed_store_display_name: str = Field(
        default="",
        description="The store name as displayed, for the operator's eyes only.",
    )
    period_start_readback: str = Field(
        default="", description="The start date the export form actually shows, YYYY-MM-DD, after you set it."
    )
    period_end_readback: str = Field(
        default="", description="The end date the export form actually shows, YYYY-MM-DD, after you set it."
    )
    download_observed: bool = Field(
        default=False, description="True only if the browser actually began/finished a file download."
    )
    note: str = Field(default="", description="One short sentence. No URLs, no selectors, no customer text.")


TASK_TEMPLATE = """\
당신은 이미 로그인된 네이버 스마트스토어 판매자센터 브라우저를 조작합니다.

목표: 판매자센터의 **공식 리뷰 엑셀 다운로드** 기능을 한 번 실행한다.

수행 순서:
1. 스마트스토어 판매자센터의 리뷰 관리 화면(문의/리뷰 관리 계열의 공식 메뉴)으로 이동한다.
2. 화면에서 이 스토어의 **안정적인 식별자**(스토어/채널 번호, 계정 번호처럼 기계가 읽을 수 있는 값)를
   찾아 observed_store_identifier 에 적는다. 표시 이름은 observed_store_display_name 에 따로 적는다.
   식별자를 못 찾으면 STORE_UNRESOLVED 로 끝낸다.
3. 조회 기간을 {start} 부터 {end} 까지로 설정한다. 설정한 뒤 **화면에 실제로 표시된 값을 다시 읽어서**
   period_start_readback / period_end_readback 에 적는다. 화면 값이 요청한 기간과 다르면 그대로 적는다.
4. 공식 엑셀 다운로드 컨트롤을 실행한다. 확인/동의 팝업이 나오면 그 팝업의 확인만 누른다.
5. 다운로드가 시작되거나 완료된 것을 확인하면 download_observed=true, verdict=EXPORT_REQUESTED 로 끝낸다.

절대 하지 않을 것:
- 로그인 화면, 재인증, 2단계 인증, 보안문자(CAPTCHA)가 나오면 **즉시 중단**하고
  verdict=AUTH_REQUIRED 로 끝낸다. 아이디·비밀번호·인증번호를 입력하거나 우회하려 시도하지 않는다.
- 리뷰 답글 작성/수정/삭제/신고, 상품 수정, 주문 처리 등 **어떤 쓰기 동작도 하지 않는다.**
- 판매자센터 공식 화면 밖으로 나가지 않는다. 검색엔진을 쓰지 않는다.
- 고객 이름·연락처·리뷰 본문을 note 나 식별자 칸에 옮겨 적지 않는다.
- URL 문자열을 결과에 적지 않는다.

엑셀 다운로드 컨트롤을 찾을 수 없으면 EXPORT_CONTROL_NOT_FOUND, 화면 상태를 판단할 수 없으면
UNSUPPORTED_STATE 로 끝낸다. 추측해서 진행하지 않는다.
"""


def build_llm(provider: str, model: str, api_key: str):
    if provider == "openai":
        from browser_use.llm import ChatOpenAI

        return ChatOpenAI(model=model, api_key=api_key)
    if provider == "anthropic":
        from browser_use.llm import ChatAnthropic

        return ChatAnthropic(model=model, api_key=api_key, max_tokens=4096)
    raise SystemExit(f"[agent] unsupported POC_LLM_PROVIDER")


async def run_export(
    *,
    cdp_url: str,
    llm,
    start: str,
    end: str,
    use_vision: bool,
    max_steps: int,
    allowed_domains: list[str],
    excluded_actions: list[str],
):
    from browser_use import Agent, Browser
    from browser_use.tools.service import Tools

    browser = Browser(cdp_url=cdp_url, is_local=False, allowed_domains=allowed_domains)
    tools = Tools(exclude_actions=excluded_actions, display_files_in_done_text=False)
    agent = Agent(
        task=TASK_TEMPLATE.format(start=start, end=end),
        llm=llm,
        browser=browser,
        tools=tools,
        use_vision=use_vision,
        output_model_schema=ExportOutcome,
    )
    history = await agent.run(max_steps=max_steps)
    return history
