#!/bin/zsh
# The evaluation backend + agent runtime over the COPY database (sellerops_eval) — docs/standing/reply_agent_v1.md §8 ①.
# Usage: start_eval_backend.sh <out dir>        stop: kill $(cat <out dir>/backend.pid <out dir>/runtime.pid)
#
# No vault key ring ⇒ no channel credential can be decrypted ⇒ no marketplace call is possible. Schedulers off.
# Every model step is Claude (owner, 2026-10-29: 「진행할 시 claude로 진행하자」): the key is the owner's own line in
# backend/.env.local — SELLEROPS_EVAL_ANTHROPIC_API_KEY — mapped inside this subprocess only and never printed. Without it
# nothing starts rather than falling back to another vendor. The retrieval helpers that speak only OpenAI (embedding)
# stay off, and so do intent and eligibility, so retrieval is the lexical path; <out>/config.txt records all of it.
set -u
OUT=$1
case "$OUT" in "$HOME/workspace/sellerops/holds/"*) ;; *) echo "out dir must be under ~/workspace/sellerops/holds/"; exit 2;; esac
REPO=${0:A:h:h:h:h}
ORG=7146c50f-ff6d-4c83-ae96-18c930e6d8e0
MODEL=${EVAL_CLAUDE_MODEL:-claude-opus-5-5}
umask 077; mkdir -p "$OUT"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
JAR=$(ls $REPO/backend/build/libs/*.jar | grep -v plain | head -1)
cat > "$OUT/config.txt" <<EOF
database: sellerops_eval (copy) · org $ORG${EVAL_EXTRA_ORG_IDS:+ + synthetic $EVAL_EXTRA_ORG_IDS}${EVAL_SYNTHETIC_VISIBLE:+ (synthetic rows visible)} · vault key ring: none
reply agent (reviews): ANTHROPIC $MODEL · effort low · max output 16000
inquiry draft: ANTHROPIC $MODEL · effort low · max output 16000 · figure check: same model
inquiry decision v2 (needs + coverage judge): ANTHROPIC $MODEL · judge prompt ${EVAL_JUDGE_PROMPT:-coverage-judge/v4}
knowledge embedding: off (OpenAI only) · question intent: off · evidence eligibility: off  ⇒ lexical retrieval
old review triage classifier: off (OpenAI only; the reply agent's own judgement replaces it)
llm send gate: on (server default)
backend jar: $(basename "$JAR") $(stat -f %Sm -t %Y-%m-%dT%H:%M "$JAR")
commit: $(git -C $REPO rev-parse --short HEAD) + uncommitted working tree
EOF
(
  cd $REPO/backend
  set -a; [ -f .env.local ] && . ./.env.local; set +a
  unset SELLEROPS_VAULT_KEY_RING
  if [ -z "${SELLEROPS_EVAL_ANTHROPIC_API_KEY:-}" ]; then echo "SELLEROPS_EVAL_ANTHROPIC_API_KEY is not set"; exit 3; fi
  K=$SELLEROPS_EVAL_ANTHROPIC_API_KEY; unset SELLEROPS_EVAL_ANTHROPIC_API_KEY
  export SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=false COLLECT_SCHEDULER_ENABLED=false SELF_PILOT_ENABLED=false
  export SELLEROPS_COLLECT_SCHEDULER_ENABLED=false SELLEROPS_PROACTIVE_ENABLED=false SELLEROPS_AI_TRIAGE_PILOT_ENABLED=false
  export SELLEROPS_VENDOR_REVIEW_PULL_ENABLED=false
  # A synthetic store's rows (VERIFY_FIXTURE) are hidden from every read unless the deployment opts in; only the synthetic
  # round turns this on. It seeds nothing into a database that already has organisations (MockDataSeeder).
  [ -n "${EVAL_SYNTHETIC_VISIBLE:-}" ] && export SELLEROPS_SEED_DEMO_CONTENT=true
  export SELLEROPS_AGENT_RUNTIME_URL=http://127.0.0.1:18787
  export SELLEROPS_AGENT_REPLY_ENABLED=true SELLEROPS_AGENT_REPLY_ORG_IDS=$ORG${EVAL_EXTRA_ORG_IDS:+,$EVAL_EXTRA_ORG_IDS}
  export SELLEROPS_AGENT_REPLY_VENDOR=ANTHROPIC SELLEROPS_AGENT_REPLY_MODEL=$MODEL SELLEROPS_AGENT_REPLY_API_KEY=$K
  export SELLEROPS_AGENT_REPLY_REASONING_EFFORT=low SELLEROPS_AGENT_REPLY_MAX_OUTPUT_TOKENS=16000
  export SELLEROPS_AGENT_DRAFT_ENABLED=true SELLEROPS_AGENT_DRAFT_ORG_IDS=$ORG${EVAL_EXTRA_ORG_IDS:+,$EVAL_EXTRA_ORG_IDS}
  export SELLEROPS_AGENT_DRAFT_VENDOR=ANTHROPIC SELLEROPS_AGENT_DRAFT_MODEL=$MODEL SELLEROPS_AGENT_DRAFT_API_KEY=$K
  export SELLEROPS_AGENT_DRAFT_REASONING_EFFORT=low SELLEROPS_AGENT_DRAFT_MAX_OUTPUT_TOKENS=16000
  export SELLEROPS_INQUIRY_DECISION_ENABLED=true SELLEROPS_INQUIRY_DECISION_ORG_IDS=$ORG${EVAL_EXTRA_ORG_IDS:+,$EVAL_EXTRA_ORG_IDS}
  export SELLEROPS_INQUIRY_DECISION_VENDOR=ANTHROPIC SELLEROPS_INQUIRY_DECISION_MODEL=$MODEL
  export SELLEROPS_INQUIRY_DECISION_API_KEY=$K SELLEROPS_INQUIRY_DECISION_JUDGE_PROMPT=${EVAL_JUDGE_PROMPT:-coverage-judge/v4}
  export SELLEROPS_KNOWLEDGE_EMBEDDING_ENABLED=false SELLEROPS_KNOWLEDGE_INTENT_ENABLED=false
  export SELLEROPS_KNOWLEDGE_ELIGIBILITY_ENABLED=false
  unset K
  exec java -jar "$JAR" --server.port=18080 --spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/sellerops_eval
) > "$OUT/backend.log" 2>&1 &
echo $! > "$OUT/backend.pid"
(
  cd $REPO/agent-runtime
  set -a; [ -f .env ] && . ./.env; set +a
  export AGENT_RUNTIME_PORT=18787 SELLEROPS_BASE_URL=http://127.0.0.1:18080
  exec npx tsx src/http/main.ts
) > "$OUT/runtime.log" 2>&1 &
echo $! > "$OUT/runtime.pid"
echo "started; config in $OUT/config.txt"
