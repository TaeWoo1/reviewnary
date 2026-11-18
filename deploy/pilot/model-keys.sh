# Which key a model capability will actually boot with — one rule, sourced by deploy.sh and preflight.sh.
#   . deploy/pilot/model-keys.sh; model_key_problems    # prints one problem per line, variable NAMES only
#
# Two ways to reach a model, and the env file says which:
#   * direct vendor — each capability's own SELLEROPS_<CAP>_API_KEY (and _VENDOR / _MODEL).
#   * the Runyour router — SPRING_PROFILES_INCLUDE names `runyour` (application-runyour.yml) and ONE key,
#     RUNYOUR_API_KEY, carries every capability the profile routes.
#
# How the key reaches the container: docker-compose.yml passes every SELLEROPS_<CAP>_API_KEY blank-by-default
# and never fills it from RUNYOUR_API_KEY (a plain `docker compose up` without the profile must not hand the
# router key to a direct vendor). Under the profile the BACKEND fills a blank key in memory, only for a
# capability whose vendor resolved to RUNYOUR (RunyourRouterKeyPostProcessor). So the effective key is: its
# own, or — profile on, vendor RUNYOUR — RUNYOUR_API_KEY. compose's investigator default (OPENAI) outranks
# the profile, so that capability needs its vendor named explicitly or it stays keyless.

# What it does not do: lower or replace any other check. Enabled/organisation rules, `*` refusals and the
# spending caps stay where they are in the callers; nothing here reads or prints a key value.

# The capabilities deploy.sh has always required a key for. Each has a SELLEROPS_<CAP>_API_KEY line in
# docker-compose.yml (blank by default) and each is routed by application-runyour.yml.
MODEL_KEY_CAPS=(AGENT_PLAN AGENT_DRAFT AGENT_JUDGE AGENT_CONVERSE AGENT_REPORT KNOWLEDGE_EMBEDDING KNOWLEDGE_INTENT
  KNOWLEDGE_ELIGIBILITY REVIEW_MEDIA_VISION IMAGE_KNOWLEDGE INQUIRY_GOAL INQUIRY_DECISION INQUIRY_SIGNATURE RESPONSIBILITY_INVESTIGATION)

# True when SPRING_PROFILES_INCLUDE names the runyour profile exactly (comma and/or space separated, as Spring reads it).
runyour_on() {
  local t
  local v="${SPRING_PROFILES_INCLUDE:-}"
  for t in ${v//,/ }; do [[ "$t" == "runyour" ]] && return 0; done
  return 1
}

# The key one capability will boot with under the profile: its own, else RUNYOUR_API_KEY (see above).
effective_model_key() {  # effective_model_key AGENT_DRAFT
  local k="SELLEROPS_${1}_API_KEY"
  printf '%s' "${!k:-${RUNYOUR_API_KEY:-}}"
}

model_key_problems() {
  local cap e k
  if runyour_on; then
    for cap in "${MODEL_KEY_CAPS[@]}"; do
      e="SELLEROPS_${cap}_ENABLED"; k="SELLEROPS_${cap}_API_KEY"
      # Under the profile the capability's vendor is RUNYOUR, so its own key would be SENT TO THE ROUTER.
      [[ -z "${!k:-}" ]] || echo "$k is set while SPRING_PROFILES_INCLUDE=runyour routes ${cap} through Runyour — that key would be sent to the router; leave it blank (RUNYOUR_API_KEY carries it)"
      if [[ "${!e:-false}" == "true" && -z "$(effective_model_key "$cap")" ]]; then
        echo "$e=true but RUNYOUR_API_KEY is blank (SPRING_PROFILES_INCLUDE=runyour carries every routed capability's key)"
      fi
    done
    # The reply agent and AI triage read the profile's key directly (their compose names are not the names the
    # profile binds), so under the profile RUNYOUR_API_KEY is their key too.
    if [[ "${SELLEROPS_AGENT_REPLY_ENABLED:-false}" == "true" && -z "${RUNYOUR_API_KEY:-}" ]]; then
      echo "SELLEROPS_AGENT_REPLY_ENABLED=true but RUNYOUR_API_KEY is blank (SPRING_PROFILES_INCLUDE=runyour)"
    fi
    if [[ "${SELLEROPS_AI_TRIAGE_PILOT_ENABLED:-false}" == "true" && -z "${RUNYOUR_API_KEY:-}" ]]; then
      echo "SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true but RUNYOUR_API_KEY is blank (SPRING_PROFILES_INCLUDE=runyour) — AI triage would classify nothing and say nothing"
    fi
    # docker-compose.yml gives the investigator a direct default (OPENAI / an OpenAI model id), and an OS
    # variable outranks the profile: the backend then gives it no router key. Name the route explicitly.
    if [[ "${SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED:-false}" == "true" ]]; then
      [[ "${SELLEROPS_RESPONSIBILITY_INVESTIGATION_VENDOR:-}" == "RUNYOUR" ]] \
        || echo "SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED=true under runyour but SELLEROPS_RESPONSIBILITY_INVESTIGATION_VENDOR is not RUNYOUR — docker-compose.yml's OPENAI default outranks the profile, so it would boot with no key"
      [[ -n "${SELLEROPS_RESPONSIBILITY_INVESTIGATION_MODEL:-}" ]] \
        || echo "SELLEROPS_RESPONSIBILITY_INVESTIGATION_ENABLED=true under runyour but SELLEROPS_RESPONSIBILITY_INVESTIGATION_MODEL is blank — docker-compose.yml's OpenAI model id would be sent to the router"
    fi
    if [[ "${SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED:-false}" == "true" && -z "${RUNYOUR_EMBEDDING_MODEL:-}" ]]; then
      echo "SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED=true under runyour but RUNYOUR_EMBEDDING_MODEL is blank — a chat model id is not an embedding model"
    fi
  else
    [[ -z "${RUNYOUR_API_KEY:-}" ]] \
      || echo "RUNYOUR_API_KEY is set but SPRING_PROFILES_INCLUDE does not name runyour — the key would be read by nothing (every capability stays on its direct vendor); add the profile or clear the key"
    for cap in "${MODEL_KEY_CAPS[@]}"; do
      e="SELLEROPS_${cap}_ENABLED"; k="SELLEROPS_${cap}_API_KEY"
      if [[ "${!e:-false}" == "true" && -z "${!k:-}" ]]; then echo "$e=true but $k is blank"; fi
    done
    if [[ "${SELLEROPS_AGENT_REPLY_ENABLED:-false}" == "true" && -z "${SELLEROPS_AGENT_REPLY_API_KEY:-${SELLEROPS_AGENT_DRAFT_API_KEY:-}}" ]]; then
      echo "SELLEROPS_AGENT_REPLY_ENABLED=true but neither SELLEROPS_AGENT_REPLY_API_KEY nor SELLEROPS_AGENT_DRAFT_API_KEY is set"
    fi
    if [[ "${SELLEROPS_AI_TRIAGE_PILOT_ENABLED:-false}" == "true" && -z "${SELLEROPS_AI_TRIAGE_API_KEY:-}" ]]; then
      echo "SELLEROPS_AI_TRIAGE_PILOT_ENABLED=true but SELLEROPS_AI_TRIAGE_API_KEY is blank — AI triage would classify nothing and say nothing"
    fi
  fi
}
