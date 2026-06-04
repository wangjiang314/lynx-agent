#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.juwan.lynx"
LAUNCH_ACTIVITY="com.juwan.lynx/.ui.ChatPanelActivity"
APP_TRACE_FILE="/sdcard/Android/data/${PACKAGE_NAME}/files/lynxflow/lynxflow.log"
OUTPUT_ROOT="artifacts/benchmarks"
SCENARIO_ID="manual"
INSTRUCTION=""
DURATION_SEC=180
PREFLIGHT_GRACE_SEC=15
COMPLETION_GATE_GRACE_SEC=45
DEVICE_SERIAL=""
APK_PATH=""
AUTO_LAUNCH=0
AUTO_RUN=0
FORCE_STOP_APP=0
FROM_LOG=""
CLEAR_LOGCAT=1

usage() {
  cat <<'EOF'
Usage:
  tools/run_device_benchmark.sh [options]

Options:
  --scenario-id ID        Scenario identifier used in report naming.
  --instruction TEXT      Natural-language task instruction under validation.
  --duration SEC          Log capture duration in seconds. Default: 180.
  --serial SERIAL         adb device serial.
  --apk PATH              Optional debug APK to install before capture.
  --launch                Launch ChatPanelActivity before capture.
  --auto-run              Launch debug app with --instruction as an intent extra.
  --force-stop-app        Force-stop Lynx before launch. Off by default because it can kill accessibility service state.
  --from-log PATH         Parse an existing LynxFlow log file instead of capturing via adb.
  --output-root DIR       Output directory root. Default: artifacts/benchmarks.
  --no-clear              Do not clear adb logcat before capture.
  --help                  Show this message.

Examples:
  tools/run_device_benchmark.sh \
    --scenario-id B03_message_send \
    --instruction "打开目标应用完成示例任务" \
    --serial QXNUT21B09005099 \
    --launch

  tools/run_device_benchmark.sh \
    --scenario-id replay_parse \
    --from-log artifacts/benchmarks/sample/lynxflow.log
EOF
}

die() {
  echo "Error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario-id)
      SCENARIO_ID="${2:-}"
      shift 2
      ;;
    --instruction)
      INSTRUCTION="${2:-}"
      shift 2
      ;;
    --duration)
      DURATION_SEC="${2:-}"
      shift 2
      ;;
    --serial)
      DEVICE_SERIAL="${2:-}"
      shift 2
      ;;
    --apk)
      APK_PATH="${2:-}"
      shift 2
      ;;
    --launch)
      AUTO_LAUNCH=1
      shift
      ;;
    --auto-run)
      AUTO_RUN=1
      AUTO_LAUNCH=1
      shift
      ;;
    --force-stop-app)
      FORCE_STOP_APP=1
      shift
      ;;
    --from-log)
      FROM_LOG="${2:-}"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="${2:-}"
      shift 2
      ;;
    --no-clear)
      CLEAR_LOGCAT=0
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ "$DURATION_SEC" =~ ^[0-9]+$ ]] || die "--duration must be a non-negative integer"

timestamp="$(date +%Y%m%d_%H%M%S)"
sanitized_scenario="$(printf '%s' "$SCENARIO_ID" | tr '[:space:]/' '__' | tr -cd '[:alnum:]_-.')"
[[ -n "$sanitized_scenario" ]] || sanitized_scenario="manual"
RUN_ID="${sanitized_scenario}_${timestamp}"
run_dir="${OUTPUT_ROOT}/${timestamp}_${sanitized_scenario}"
mkdir -p "$run_dir"

log_file="${run_dir}/lynxflow.log"
file_trace_log="${run_dir}/lynxflow.file.log"
selected_log_file="${run_dir}/lynxflow.selected.log"
metrics_csv="${run_dir}/auto_metrics.csv"
report_md="${run_dir}/benchmark_report.md"
meta_file="${run_dir}/benchmark_meta.env"

write_meta() {
  cat >"$meta_file" <<EOF
SCENARIO_ID=${SCENARIO_ID}
INSTRUCTION=${INSTRUCTION}
DURATION_SEC=${DURATION_SEC}
DEVICE_SERIAL=${DEVICE_SERIAL}
APK_PATH=${APK_PATH}
AUTO_LAUNCH=${AUTO_LAUNCH}
AUTO_RUN=${AUTO_RUN}
FORCE_STOP_APP=${FORCE_STOP_APP}
FROM_LOG=${FROM_LOG}
OUTPUT_ROOT=${OUTPUT_ROOT}
RUN_DIR=${run_dir}
RAW_LOG=${log_file}
FILE_TRACE_LOG=${file_trace_log}
SELECTED_TRACE_LOG=${selected_log_file}
RUN_ID=${RUN_ID}
EOF
}

adb_base=(adb)
if [[ -n "$DEVICE_SERIAL" ]]; then
  adb_base+=(-s "$DEVICE_SERIAL")
fi

run_adb() {
  "${adb_base[@]}" "$@" </dev/null
}

send_debug_cancel_active_task() {
  local reason="${1:-benchmark_cancel}"
  run_adb shell am start -W -n "$LAUNCH_ACTIVITY" \
    --ez lynx.debug_cancel_active_task true \
    --es lynx.debug_cancel_reason "$reason" >/dev/null 2>&1 || true
}

launch_debug_app() {
  if [[ "$AUTO_RUN" -eq 1 ]]; then
    [[ -n "$INSTRUCTION" ]] || die "--auto-run requires --instruction"
    local encoded_instruction
    encoded_instruction="$(printf '%s' "$INSTRUCTION" | base64 | tr -d '\n')"
    run_adb shell am start -W -n "$LAUNCH_ACTIVITY" \
      --es lynx.debug_instruction_b64 "$encoded_instruction" \
      --es lynx.debug_run_id "$RUN_ID" \
      --es lynx.debug_scenario_id "$SCENARIO_ID" >/dev/null
  else
    run_adb shell am start -W -n "$LAUNCH_ACTIVITY" >/dev/null
  fi
}

is_accessibility_permission_enabled() {
  run_adb shell settings get secure enabled_accessibility_services \
    | tr -d '\r' \
    | grep -q "${PACKAGE_NAME}/com.juwan.lynx.service.LynxAccessibilityService"
}

wait_for_accessibility_permission_enabled() {
  local timeout_sec="${1:-8}"
  local deadline=$(( $(date +%s) + timeout_sec ))
  while true; do
    if is_accessibility_permission_enabled; then
      return 0
    fi
    if (( $(date +%s) >= deadline )); then
      return 1
    fi
    sleep 1
  done
}

clear_file_trace_snapshot() {
  run_adb shell rm -f "$APP_TRACE_FILE" >/dev/null 2>&1 || true
}

normalize_trace_log() {
  local source_file="$1"
  local target_file="$2"
  LC_ALL=C tr -d '\000' <"$source_file" >"$target_file" 2>/dev/null || true
}

pull_file_trace_snapshot() {
  run_adb pull "$APP_TRACE_FILE" "$file_trace_log" >/dev/null 2>&1 || true
  if [[ -s "$file_trace_log" ]] && LC_ALL=C grep -a -q "run_id=${RUN_ID}" "$file_trace_log"; then
    cp "$file_trace_log" "$log_file"
  fi
}

archive_and_clear_file_trace_snapshot() {
  pull_file_trace_snapshot
  clear_file_trace_snapshot
}

extract_latest_trace() {
  local file="$1"
  local trace
  trace="$(grep 'stage=run_start' "$file" 2>/dev/null | tail -n1 | sed -n 's/.*trace=\([^ ]*\).*/\1/p' || true)"
  if [[ -z "$trace" ]]; then
    trace="$(grep -o 'trace=[^ ]*' "$file" 2>/dev/null | tail -n1 | sed 's/^trace=//' || true)"
  fi
  printf '%s' "$trace"
}

trace_has_stage() {
  local file="$1"
  local trace="$2"
  local stage="$3"
  normalize_trace_log "$file" /dev/stdout | awk -v trace="$trace" -v stage="$stage" '
    index($0, "trace=" trace " ") > 0 && index($0, "stage=" stage) > 0 {
      found = 1
      exit
    }
    END { exit found ? 0 : 1 }
  '
}

trace_has_finish_proposed() {
  local file="$1"
  local trace="$2"
  normalize_trace_log "$file" /dev/stdout | awk -v trace="$trace" '
    index($0, "trace=" trace " ") > 0 &&
      index($0, "stage=executor_result") > 0 &&
      index($0, "type=FinishProposed") > 0 {
        found = 1
        exit
      }
    END { exit found ? 0 : 1 }
  '
}

extract_target_trace() {
  local file="$1"
  local trace=""
  if [[ -n "${RUN_ID:-}" ]]; then
    trace="$(
      normalize_trace_log "$file" /dev/stdout |
        awk -v expected="$RUN_ID" '
        function trace_value(line, value) {
          if (match(line, /trace=[^ ]+/)) {
            value = substr(line, RSTART, RLENGTH)
            sub(/^trace=/, "", value)
            return value
          }
          return ""
        }
        index($0, "stage=run_start") > 0 && index($0, "run_id=" expected) > 0 {
          value = trace_value($0)
          if (value != "") {
            order[++count] = value
            seen[value] = 1
          }
        }
        index($0, "stage=run_finish") > 0 {
          value = trace_value($0)
          if (value != "") {
            finished[value] = 1
          }
        }
        END {
          if (count > 0) {
            print order[count]
          }
        }
      ' 2>/dev/null
    )"
    if [[ -z "$trace" && -z "${FROM_LOG:-}" ]]; then
      printf ''
      return 0
    fi
  fi
  if [[ -n "$INSTRUCTION" ]]; then
    if [[ -z "$trace" ]]; then
      trace="$(
        normalize_trace_log "$file" /dev/stdout |
          awk -v expected="$INSTRUCTION" '
          function trace_value(line, value) {
            if (match(line, /trace=[^ ]+/)) {
              value = substr(line, RSTART, RLENGTH)
              sub(/^trace=/, "", value)
              return value
            }
            return ""
          }
          index($0, "stage=run_start") > 0 && index($0, "instruction=" expected) > 0 {
            value = trace_value($0)
            if (value != "") {
              order[++count] = value
              seen[value] = 1
            }
          }
          index($0, "stage=run_finish") > 0 {
            value = trace_value($0)
            if (value != "") {
              finished[value] = 1
            }
          }
          END {
            if (count > 0) {
              print order[count]
            }
          }
        ' 2>/dev/null
      )"
    fi
  fi
  if [[ -z "$trace" ]]; then
    trace="$(
      normalize_trace_log "$file" /dev/stdout |
        extract_latest_trace /dev/stdin
    )"
  fi
  printf '%s' "$trace"
}

count_distinct_traces() {
  local file="$1"
  grep -o 'trace=[^ ]*' "$file" 2>/dev/null | sed 's/^trace=//' | sort -u | wc -l | tr -d ' '
}

isolate_trace_log() {
  local file="$1"
  local trace="$2"
  grep "trace=${trace} " "$file" >"$selected_log_file" 2>/dev/null || true
}

extract_run_finish_field() {
  local file="$1"
  local trace="$2"
  local key="$3"
  grep "trace=${trace} " "$file" \
    | grep 'stage=run_finish' \
    | tail -n1 \
    | sed -n "s/.*${key}=\\([^ ]*\\).*/\\1/p" || true
}

count_matching_lines() {
  local file="$1"
  local trace="$2"
  local pattern="$3"
  grep "trace=${trace} " "$file" | grep -c "$pattern" || true
}

count_actions() {
  local file="$1"
  local trace="$2"
  local mode="$3"
  awk -v trace="$trace" -v mode="$mode" '
    index($0, "trace=" trace " ") > 0 && index($0, "stage=world_update_after_action") > 0 {
      action = $0
      sub("^.*action=", "", action)
      sub("[^A-Za-z_].*$", "", action)
      if (action != "") {
        total += 1
        if (mode == "repeat" && prev == action) {
          count += 1
        }
        if (mode == "changed" && index($0, "changed=true") > 0) {
          count += 1
        }
        prev = action
      }
    }
    END {
      if (mode == "total") {
        print total + 0
      } else {
        print count + 0
      }
    }
  ' "$file"
}

extract_last_completion_gate_accepted() {
  local file="$1"
  local trace="$2"
  grep "trace=${trace} " "$file" \
    | grep 'stage=completion_gate_result' \
    | tail -n1 \
    | sed -n 's/.*accepted=\([^ ]*\).*/\1/p' || true
}

count_world_updates_missing_tool_status() {
  local file="$1"
  local trace="$2"
  awk -v trace="$trace" '
    index($0, "trace=" trace " ") > 0 &&
      index($0, "stage=world_update_after_action") > 0 &&
      index($0, "tool_result_status=") == 0 {
        count += 1
      }
    END { print count + 0 }
  ' "$file"
}

extract_preflight_block_reason() {
  local file="$1"
  local trace="$2"
  grep "trace=${trace} " "$file" \
    | grep 'stage=task_preflight_blocked' \
    | tail -n1 \
    | sed -n 's/.*reason=\([^ ]*\).*/\1/p' || true
}

format_ratio() {
  local numerator="$1"
  local denominator="$2"
  awk -v n="$numerator" -v d="$denominator" 'BEGIN {
    if (d == 0) {
      printf "0.00"
    } else {
      printf "%.2f", n / d
    }
  }'
}

generate_report() {
  local trace="$1"
  local status="$2"
  local duration="$3"
  local api_calls="$4"
  local tool_calls="$5"
  local prompt_tokens="$6"
  local completion_tokens="$7"
  local total_tokens="$8"
  local executor_iterations="$9"
  local total_actions="${10}"
  local repeated_actions="${11}"
  local repeated_ratio="${12}"
  local changed_actions="${13}"
  local finish_rejects="${14}"
  local finish_recoveries="${15}"
  local progress_events="${16}"
  local replan_events="${17}"
  local verifier_events="${18}"
  local verifier_rejects="${19}"
  local terminal_code="${20}"
  local deterministic_events="${21}"
  local trace_integrity_status="${22}"
  local raw_trace_count="${23}"
  local selected_log_lines="${24}"
  local run_start_events="${25}"
  local run_finish_events="${26}"
  local completion_gate_accepted_events="${27}"
  local completion_gate_rejected_events="${28}"
  local last_completion_gate_accepted="${29}"
  local structured_tool_result_missing="${30}"
  local trace_integrity_reason="${31}"

  cat >"$report_md" <<EOF
# Device Benchmark Report

## Metadata

- Scenario ID: ${SCENARIO_ID}
- Instruction: ${INSTRUCTION:-manual_entry_required}
- Trace ID: ${trace}
- Benchmark Run ID: ${RUN_ID}
- Status: ${status}
- Terminal Code: ${terminal_code}
- Raw Log: ${log_file}
- Selected Trace Log: ${selected_log_file}
- Metrics CSV: ${metrics_csv}
- Guardrail Integrity Status: ${trace_integrity_status}

## Auto Metrics

| Metric | Value |
| --- | --- |
| duration_sec | ${duration} |
| api_calls | ${api_calls} |
| tool_calls | ${tool_calls} |
| prompt_tokens | ${prompt_tokens} |
| completion_tokens | ${completion_tokens} |
| total_tokens | ${total_tokens} |
| executor_iterations | ${executor_iterations} |
| total_actions | ${total_actions} |
| repeated_actions | ${repeated_actions} |
| repeated_action_ratio | ${repeated_ratio} |
| changed_actions | ${changed_actions} |
| finish_handshake_rejects | ${finish_rejects} |
| finish_handshake_recoveries | ${finish_recoveries} |
| insufficient_progress_events | ${progress_events} |
| replan_events | ${replan_events} |
| verifier_events | ${verifier_events} |
| verifier_rejects | ${verifier_rejects} |
| deterministic_completion_events | ${deterministic_events} |
| completion_gate_accepted_events | ${completion_gate_accepted_events} |
| completion_gate_rejected_events | ${completion_gate_rejected_events} |
| last_completion_gate_accepted | ${last_completion_gate_accepted:-none} |
| structured_tool_result_missing | ${structured_tool_result_missing} |
| trace_integrity_status | ${trace_integrity_status} |
| trace_integrity_reason | ${trace_integrity_reason} |
| raw_trace_count | ${raw_trace_count} |
| selected_log_lines | ${selected_log_lines} |
| run_start_events | ${run_start_events} |
| run_finish_events | ${run_finish_events} |

## Manual Checks

- User-visible task success: TODO
- Expected end state reached: TODO
- Finish false reject observed: TODO
- Unexpected loop/noise observed: TODO
- Popup recovery needed: TODO
- Notes: TODO
EOF
}

summarize_log() {
  local raw_file="$1"
  local normalized_file="${run_dir}/lynxflow.normalized.log"
  normalize_trace_log "$raw_file" "$normalized_file"
  local trace
  trace="$(extract_target_trace "$normalized_file")"
  [[ -n "$trace" ]] || die "no LynxFlow trace found in $raw_file"
  isolate_trace_log "$normalized_file" "$trace"
  [[ -s "$selected_log_file" ]] || die "selected trace has no log lines: $trace"
  local file="$selected_log_file"

  local status duration api_calls tool_calls terminal_code prompt_tokens completion_tokens total_tokens
  status="$(extract_run_finish_field "$file" "$trace" "status")"
  duration="$(extract_run_finish_field "$file" "$trace" "duration_sec")"
  api_calls="$(extract_run_finish_field "$file" "$trace" "api_calls")"
  tool_calls="$(extract_run_finish_field "$file" "$trace" "tool_calls")"
  prompt_tokens="$(extract_run_finish_field "$file" "$trace" "prompt_tokens")"
  completion_tokens="$(extract_run_finish_field "$file" "$trace" "completion_tokens")"
  total_tokens="$(extract_run_finish_field "$file" "$trace" "total_tokens")"
  terminal_code="$(
    grep "trace=${trace} " "$file" \
      | grep 'stage=executor_result' \
      | tail -n1 \
      | sed -n 's/.*code=\([^ ]*\).*/\1/p' || true
  )"

  local executor_iterations total_actions repeated_actions changed_actions repeated_ratio
  local finish_rejects finish_recoveries progress_events replan_events
  local verifier_events verifier_rejects
  local deterministic_events
  local completion_gate_accepted_events completion_gate_rejected_events last_completion_gate_accepted
  local structured_tool_result_missing
  local raw_trace_count selected_log_lines run_start_events run_finish_events
  local trace_integrity_status trace_integrity_reason preflight_block_reason

  executor_iterations="$(count_matching_lines "$file" "$trace" 'stage=executor_iteration')"
  total_actions="$(count_actions "$file" "$trace" 'total')"
  repeated_actions="$(count_actions "$file" "$trace" 'repeat')"
  changed_actions="$(count_actions "$file" "$trace" 'changed')"
  repeated_ratio="$(format_ratio "$repeated_actions" "$total_actions")"
  finish_rejects="$(grep "trace=${trace} " "$file" | grep -c 'FINISH_HANDSHAKE_REJECTED' || true)"
  finish_recoveries="$(grep "trace=${trace} " "$file" | grep -Ec 'stage=finish_handshake_(local|retry)_recovery' || true)"
  progress_events="$(count_matching_lines "$file" "$trace" 'stage=executor_insufficient_progress')"
  replan_events="$(count_matching_lines "$file" "$trace" 'stage=planner_replan')"
  verifier_events="$(grep "trace=${trace} " "$file" | grep -Ec 'stage=(verifier|completion_gate)' || true)"
  verifier_rejects="$(grep "trace=${trace} " "$file" | grep -Ec 'stage=(verifier|completion_gate).*complete=false|stage=(verifier|completion_gate).*accepted=false' || true)"
  deterministic_events="$(count_matching_lines "$file" "$trace" 'stage=deterministic_completion_decision')"
  completion_gate_accepted_events="$(grep "trace=${trace} " "$file" | grep -c 'stage=completion_gate_result .*accepted=true' || true)"
  completion_gate_rejected_events="$(grep "trace=${trace} " "$file" | grep -c 'stage=completion_gate_result .*accepted=false' || true)"
  last_completion_gate_accepted="$(extract_last_completion_gate_accepted "$file" "$trace")"
  structured_tool_result_missing="$(count_world_updates_missing_tool_status "$file" "$trace")"
  raw_trace_count="$(count_distinct_traces "$normalized_file")"
  selected_log_lines="$(wc -l <"$selected_log_file" | tr -d ' ')"
  run_start_events="$(count_matching_lines "$file" "$trace" 'stage=run_start')"
  run_finish_events="$(count_matching_lines "$file" "$trace" 'stage=run_finish')"
  preflight_block_reason="$(extract_preflight_block_reason "$file" "$trace")"
  trace_integrity_reason="ok"
  if [[ "$run_start_events" != "1" ]]; then
    trace_integrity_status="fail"
    trace_integrity_reason="expected_one_run_start"
  elif [[ "$run_finish_events" != "1" ]]; then
    trace_integrity_status="fail"
    if [[ -n "$preflight_block_reason" ]]; then
      trace_integrity_reason="preflight_blocked_${preflight_block_reason}"
    else
      trace_integrity_reason="expected_one_run_finish"
    fi
  elif [[ -z "$status" ]]; then
    trace_integrity_status="fail"
    trace_integrity_reason="missing_run_finish_status"
  elif [[ "$structured_tool_result_missing" != "0" ]]; then
    trace_integrity_status="fail"
    trace_integrity_reason="missing_structured_tool_result_status"
  elif [[ "$status" == "success" && "$completion_gate_accepted_events" == "0" ]]; then
    trace_integrity_status="fail"
    trace_integrity_reason="success_without_completion_gate_accept"
  elif [[ "$status" == "success" && "$last_completion_gate_accepted" != "true" ]]; then
    trace_integrity_status="fail"
    trace_integrity_reason="success_after_nonaccepted_completion_gate"
  else
    trace_integrity_status="pass"
  fi

  cat >"$metrics_csv" <<EOF
scenario_id,run_id,trace_id,status,terminal_code,duration_sec,api_calls,tool_calls,prompt_tokens,completion_tokens,total_tokens,executor_iterations,total_actions,repeated_actions,repeated_action_ratio,changed_actions,finish_handshake_rejects,finish_handshake_recoveries,insufficient_progress_events,replan_events,verifier_events,verifier_rejects,deterministic_completion_events,completion_gate_accepted_events,completion_gate_rejected_events,last_completion_gate_accepted,structured_tool_result_missing,trace_integrity_status,trace_integrity_reason,raw_trace_count,selected_log_lines,run_start_events,run_finish_events
${SCENARIO_ID},${RUN_ID},${trace},${status:-unknown},${terminal_code:-NONE},${duration:-0},${api_calls:-0},${tool_calls:-0},${prompt_tokens:-0},${completion_tokens:-0},${total_tokens:-0},${executor_iterations},${total_actions},${repeated_actions},${repeated_ratio},${changed_actions},${finish_rejects},${finish_recoveries},${progress_events},${replan_events},${verifier_events},${verifier_rejects},${deterministic_events},${completion_gate_accepted_events},${completion_gate_rejected_events},${last_completion_gate_accepted:-none},${structured_tool_result_missing},${trace_integrity_status},${trace_integrity_reason},${raw_trace_count},${selected_log_lines},${run_start_events},${run_finish_events}
EOF

  generate_report \
    "$trace" "${status:-unknown}" "${duration:-0}" "${api_calls:-0}" "${tool_calls:-0}" \
    "${prompt_tokens:-0}" "${completion_tokens:-0}" "${total_tokens:-0}" \
    "$executor_iterations" "$total_actions" "$repeated_actions" "$repeated_ratio" \
    "$changed_actions" "$finish_rejects" "$finish_recoveries" "$progress_events" \
    "$replan_events" "$verifier_events" "$verifier_rejects" "${terminal_code:-NONE}" \
    "$deterministic_events" "$trace_integrity_status" "$raw_trace_count" \
    "$selected_log_lines" "$run_start_events" "$run_finish_events" \
    "$completion_gate_accepted_events" "$completion_gate_rejected_events" \
    "${last_completion_gate_accepted:-none}" "$structured_tool_result_missing" \
    "$trace_integrity_reason"

  echo "Benchmark parsed successfully."
  echo "  trace_id: $trace"
  echo "  metrics : $metrics_csv"
  echo "  report  : $report_md"
}

capture_logcat() {
  command -v adb >/dev/null 2>&1 || die "adb not found"
  run_adb get-state >/dev/null 2>&1 || die "adb device not available"

  if [[ -n "$APK_PATH" ]]; then
    [[ -f "$APK_PATH" ]] || die "apk not found: $APK_PATH"
    run_adb install -r -t "$APK_PATH" >/dev/null
  fi

  if [[ "$FORCE_STOP_APP" -eq 1 ]]; then
    run_adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
  fi

  if [[ "$CLEAR_LOGCAT" -eq 1 ]]; then
    run_adb logcat -c >/dev/null 2>&1 || true
  fi
  clear_file_trace_snapshot

  echo "Starting LynxFlow capture for scenario=${SCENARIO_ID} duration=${DURATION_SEC}s"
  echo "Output directory: $run_dir"

  local logcat_pid timer_pid finish_watch_pid
  run_adb logcat -v brief -s LynxFlow:I '*:S' >"$log_file" &
  logcat_pid=$!
  sleep 0.2

  cleanup_capture() {
    if [[ -n "${finish_watch_pid:-}" ]]; then
      kill "$finish_watch_pid" >/dev/null 2>&1 || true
    fi
    if [[ -n "${timer_pid:-}" ]]; then
      kill "$timer_pid" >/dev/null 2>&1 || true
    fi
    kill "$logcat_pid" >/dev/null 2>&1 || true
  }
  trap cleanup_capture INT TERM

  if [[ "$AUTO_LAUNCH" -eq 1 ]]; then
    launch_debug_app
  fi

  if [[ "$AUTO_RUN" -eq 1 ]]; then
    echo "Task auto-submitted via debug intent extra."
  else
    echo "Execute the task inside the app now."
  fi

  (
    preflight_finish_seen_at=0
    preflight_accessibility_relaunch_done=0
    while kill -0 "$logcat_pid" >/dev/null 2>&1; do
      trace="$(extract_target_trace "$log_file")"
      if [[ -n "$trace" ]] && trace_has_stage "$log_file" "$trace" "run_finish"; then
        if trace_has_stage "$log_file" "$trace" "task_preflight_blocked"; then
          preflight_reason="$(extract_preflight_block_reason "$log_file" "$trace")"
          if [[ "$AUTO_RUN" -eq 1 ]] &&
            [[ "$preflight_accessibility_relaunch_done" -eq 0 ]] &&
            [[ "$preflight_reason" == "missing_accessibility_permission" ]] &&
            wait_for_accessibility_permission_enabled 8; then
            preflight_accessibility_relaunch_done=1
            echo "Preflight reported accessibility missing while permission is enabled; relaunching once after service settle."
            sleep 2
            launch_debug_app || true
            preflight_finish_seen_at=0
            sleep 1
            continue
          fi
          if [[ "$preflight_finish_seen_at" -eq 0 ]]; then
            preflight_finish_seen_at="$(date +%s)"
          fi
          now="$(date +%s)"
          if (( now - preflight_finish_seen_at < PREFLIGHT_GRACE_SEC )); then
            sleep 1
            continue
          fi
        fi
        kill "$logcat_pid" >/dev/null 2>&1 || true
        break
      fi
      sleep 1
    done
  ) &
  finish_watch_pid=$!

  if [[ "$DURATION_SEC" -gt 0 ]]; then
    (
      sleep "$DURATION_SEC"
      if [[ "$AUTO_RUN" -eq 1 ]]; then
        trace="$(extract_target_trace "$log_file")"
        if [[ -n "$trace" ]] &&
          trace_has_finish_proposed "$log_file" "$trace" &&
          ! trace_has_stage "$log_file" "$trace" "run_finish"; then
          echo "Benchmark duration reached after finish proposal; waiting ${COMPLETION_GATE_GRACE_SEC}s for verifier/gate."
          sleep "$COMPLETION_GATE_GRACE_SEC"
          if trace_has_stage "$log_file" "$trace" "run_finish"; then
            kill "$logcat_pid" >/dev/null 2>&1 || true
            exit 0
          fi
        fi
        echo "Benchmark duration exceeded; requesting active task cancellation."
        send_debug_cancel_active_task "benchmark_timeout:${RUN_ID}"
        sleep 2
      fi
      kill "$logcat_pid" >/dev/null 2>&1 || true
    ) &
    timer_pid=$!
  fi

  wait "$logcat_pid" || true
  cleanup_capture
  archive_and_clear_file_trace_snapshot
  trap - INT TERM
}

write_meta

if [[ -n "$FROM_LOG" ]]; then
  [[ -f "$FROM_LOG" ]] || die "log file not found: $FROM_LOG"
  cp "$FROM_LOG" "$log_file"
  summarize_log "$log_file"
  exit 0
fi

capture_logcat
summarize_log "$log_file"
