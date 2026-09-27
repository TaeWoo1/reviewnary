"""The browser-use agent that performs the official export on this VM's own Chromium.

There is no selector in this file and none anywhere in `agent/` — `run.py selfcheck` asserts it by
scanning this package's own source. The agent is fenced four ways before it is handed the task:

  * `allowed_domains`   — it cannot leave the NAVER seller centre (nid.naver.com is reachable ONLY so
                          that an auth screen can be RECOGNISED and reported).
  * `exclude_actions`   — no page JS, no web search, no local file access, no screenshot.
  * `use_vision=False`  — text / accessibility serialization only.
  * `output_model_schema` — the run ends in a typed verdict, not prose we would re-parse.

The downloaded file never reaches the model: `read_file` is gone, `display_files_in_done_text` is off,
and the bytes are picked up from the download directory by `run.py` after the agent has finished.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, Field


class ExportOutcome(BaseModel):
    """What the run may tell us. Note what is absent: no URL, no selector, no customer text, no path."""

    verdict: str = Field(
        description=(
            "Exactly one of: EXPORT_REQUESTED (the official Excel download control was activated), "
            "AUTH_REQUIRED (a login / re-verification / CAPTCHA screen was reached — you STOPPED), "
            "STORE_UNRESOLVED (no stable store identifier was readable), EXPORT_CONTROL_NOT_FOUND, "
            "UNSUPPORTED_STATE."
        )
    )
    observed_store_identifier: str = Field(
        default="",
        description=("The stable machine-readable store/channel/account number the seller centre shows. "
                     "Empty if none was readable. NOT the display name."),
    )
    period_start_readback: str = Field(
        default="", description="The start date the export form actually shows, YYYY-MM-DD, after you set it.")
    period_end_readback: str = Field(
        default="", description="The end date the export form actually shows, YYYY-MM-DD, after you set it.")
    download_observed: bool = Field(
        default=False, description="True only if the browser actually began/finished a file download.")
    note: str = Field(default="", description="One short sentence. No URLs, no selectors, no customer text.")


TASK_TEMPLATE = """\
당신은 이미 로그인된 네이버 스마트스토어 판매자센터 브라우저를 조작합니다.

목표: 판매자센터의 **공식 리뷰 엑셀 다운로드** 기능을 한 번 실행한다.

수행 순서:
1. 스마트스토어 판매자센터의 리뷰 관리 화면(공식 메뉴)으로 이동한다.
2. 이 스토어의 **안정적인 식별자**(스토어/채널 번호처럼 기계가 읽을 수 있는 값)를 찾아
   observed_store_identifier 에 적는다. 못 찾으면 STORE_UNRESOLVED 로 끝낸다.
3. 조회 기간을 {start} 부터 {end} 까지로 설정한다. 설정한 뒤 **화면에 실제로 표시된 값을 다시 읽어서**
   period_start_readback / period_end_readback 에 적는다. 화면 값이 요청과 다르면 그대로 적는다.
4. 공식 엑셀 다운로드 컨트롤을 실행한다. 확인/동의 팝업이 나오면 그 팝업의 확인만 누른다.
5. 다운로드가 시작/완료된 것을 확인하면 download_observed=true, verdict=EXPORT_REQUESTED 로 끝낸다.

절대 하지 않을 것:
- 로그인 화면, 재인증, 2단계 인증, 보안문자(CAPTCHA)가 나오면 **즉시 중단**하고 verdict=AUTH_REQUIRED
  로 끝낸다. 아이디·비밀번호·인증번호를 입력하거나 우회하려 시도하지 않는다.
- 리뷰 답글 작성/수정/삭제/신고, 상품 수정, 주문 처리 등 **어떤 쓰기 동작도 하지 않는다.**
- 판매자센터 공식 화면 밖으로 나가지 않는다. 검색엔진을 쓰지 않는다.
- 고객 이름·연락처·리뷰 본문을 note 나 식별자 칸에 옮겨 적지 않는다. URL 을 결과에 적지 않는다.

엑셀 다운로드 컨트롤을 찾을 수 없으면 EXPORT_CONTROL_NOT_FOUND, 화면 상태를 판단할 수 없으면
UNSUPPORTED_STATE 로 끝낸다. 추측해서 진행하지 않는다.
"""

LOGIN_CHECK_TASK = """\
네이버 스마트스토어 판매자센터 홈으로 이동해서, 지금 로그인된 상태인지만 확인하고 끝낸다.
로그인/재인증/보안문자 화면이 보이면 AUTH_REQUIRED 로 즉시 끝낸다. 아무것도 입력하지 않는다.
로그인되어 있으면 스토어의 안정적인 식별자를 읽어 보고하고 EXPORT_REQUESTED 로 끝낸다.
어떤 쓰기 동작도 하지 않는다. URL 이나 고객 정보를 결과에 적지 않는다.
"""


def build_llm(provider: str, model: str, api_key: str):
    """No fallback. A model id the vendor refuses stops the run — a quieter model is a different run."""
    if provider == "openai":
        from browser_use.llm import ChatOpenAI

        return ChatOpenAI(model=model, api_key=api_key)
    if provider == "anthropic":
        from browser_use.llm import ChatAnthropic

        return ChatAnthropic(model=model, api_key=api_key, max_tokens=4096)
    raise SystemExit("[agent] unsupported AGENT_LLM_PROVIDER")


def build_browser(*, profile_dir: Path, downloads_dir: Path, headless: bool, allowed_domains: list[str]):
    from browser_use import Browser

    return Browser(
        user_data_dir=str(profile_dir),
        downloads_path=str(downloads_dir),
        accept_downloads=True,
        headless=headless,
        allowed_domains=allowed_domains,
    )


async def run_task(*, task: str, llm, browser, use_vision: bool, max_steps: int,
                   excluded_actions: list[str]):
    from browser_use import Agent
    from browser_use.tools.service import Tools

    agent = Agent(
        task=task,
        llm=llm,
        browser=browser,
        tools=Tools(exclude_actions=excluded_actions, display_files_in_done_text=False),
        use_vision=use_vision,
        output_model_schema=ExportOutcome,
    )
    return await agent.run(max_steps=max_steps)
