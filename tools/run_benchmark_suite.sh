#!/usr/bin/env bash
set -euo pipefail

SERIAL=""
SUITE_FILE="benchmarks/suites/completion_smoke.tsv"
OUTPUT_ROOT="artifacts/benchmarks"
SUITE_ID=""
DEFAULT_ATTEMPTS=1
MAX_CONSECUTIVE_ENV_FAILURES=2

usage() {
  cat <<'EOF'
Usage:
  tools/run_benchmark_suite.sh [options]

Options:
  --suite FILE            TSV suite file. Default: benchmarks/suites/completion_smoke.tsv
  --suite-id ID           Output suite id. Default: suite file basename.
  --serial SERIAL         adb device serial.
  --output-root DIR       Benchmark output root. Default: artifacts/benchmarks.
  --attempts N            Default attempts when a row omits max_attempts. Default: 1.
  --max-env-failures N    Stop the suite after N consecutive provider/preflight failures. Default: 2; 0 disables.
  --help                  Show this message.

TSV columns:
  id  instruction  duration_sec  target_package  prep  max_attempts

Prep values:
  home, settings_home, settings_wlan_page, settings_bluetooth_page, none
EOF
}

die() {
  echo "Error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite)
      SUITE_FILE="${2:-}"
      shift 2
      ;;
    --suite-id)
      SUITE_ID="${2:-}"
      shift 2
      ;;
    --serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="${2:-}"
      shift 2
      ;;
    --attempts)
      DEFAULT_ATTEMPTS="${2:-}"
      shift 2
      ;;
    --max-env-failures)
      MAX_CONSECUTIVE_ENV_FAILURES="${2:-}"
      shift 2
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

[[ -f "$SUITE_FILE" ]] || die "suite file not found: $SUITE_FILE"
[[ "$DEFAULT_ATTEMPTS" =~ ^[0-9]+$ ]] || die "--attempts must be an integer"
[[ "$MAX_CONSECUTIVE_ENV_FAILURES" =~ ^[0-9]+$ ]] || die "--max-env-failures must be an integer"

if [[ -z "$SUITE_ID" ]]; then
  SUITE_ID="$(basename "$SUITE_FILE" .tsv)"
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
sanitized_suite="$(printf '%s' "$SUITE_ID" | tr '[:space:]/' '__' | tr -cd '[:alnum:]_-.')"
[[ -n "$sanitized_suite" ]] || sanitized_suite="suite"
suite_dir="${OUTPUT_ROOT}/suite_${timestamp}_${sanitized_suite}"
run_root="${suite_dir}/runs"
mkdir -p "$run_root"

attempts_tsv="${suite_dir}/attempts.tsv"
final_tsv="${suite_dir}/final.tsv"
all_metrics_csv="${suite_dir}/suite_metrics_all_attempts.csv"
final_metrics_csv="${suite_dir}/suite_metrics_final.csv"
report_md="${suite_dir}/suite_report.md"
abort_reason_file="${suite_dir}/abort_reason.txt"
consecutive_env_failures=0
abort_suite_for_environment=0

adb_base=(adb)
if [[ -n "$SERIAL" ]]; then
  adb_base+=(-s "$SERIAL")
fi

run_adb() {
  "${adb_base[@]}" "$@" </dev/null
}

wait_device() {
  local state
  for _ in $(seq 1 10); do
    state="$(run_adb get-state 2>/dev/null || true)"
    if [[ "$state" == "device" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

force_stop_if_present() {
  local package_name="$1"
  if [[ -n "$package_name" ]]; then
    run_adb shell am force-stop "$package_name" >/dev/null 2>&1 || true
  fi
}

prep_state() {
  local target_package="$1"
  local prep="$2"

  force_stop_if_present "$target_package"

  case "$prep" in
    ""|home)
      run_adb shell input keyevent HOME >/dev/null
      ;;
    none)
      ;;
    settings_home)
      run_adb shell am start -W -a android.settings.SETTINGS >/dev/null
      ;;
    settings_wlan_page)
      run_adb shell am start -W -a android.settings.WIFI_SETTINGS >/dev/null
      ;;
    settings_bluetooth_page)
      run_adb shell am start -W -a android.settings.BLUETOOTH_SETTINGS >/dev/null
      ;;
    *)
      die "unknown prep value: $prep"
      ;;
  esac

  sleep 2
}

csv_field() {
  local file="$1"
  local index="$2"
  tail -n 1 "$file" | awk -F, -v idx="$index" '{ print $idx }'
}

failure_category_for() {
  local metrics="$1"
  local log_file="$2"
  local status terminal integrity integrity_reason
  status="$(csv_field "$metrics" 4)"
  terminal="$(csv_field "$metrics" 5)"
  integrity="$(csv_field "$metrics" 28)"
  integrity_reason="$(csv_field "$metrics" 29)"
  if [[ "$integrity" != "pass" ]]; then
    if [[ "$integrity_reason" == preflight_blocked_* ]]; then
      printf 'environment_%s' "$integrity_reason"
    elif [[ "$integrity_reason" == "expected_one_run_finish" ]]; then
      printf 'benchmark_timeout'
    else
      printf 'trace_integrity_failed'
    fi
  elif [[ "$status" == "success" ]]; then
    printf 'none'
  elif [[ "$terminal" != "NONE" && -n "$terminal" && "$terminal" != "MODEL_EMPTY_RESPONSE" ]]; then
    printf '%s' "$terminal"
  elif [[ -f "$log_file" ]] && grep -q 'benchmark_timeout' "$log_file"; then
    printf 'benchmark_timeout'
  elif [[ -f "$log_file" ]] && grep -q 'stage=task_preflight_blocked' "$log_file"; then
    local reason
    reason="$(
      grep 'stage=task_preflight_blocked' "$log_file" \
        | tail -n1 \
        | sed -n 's/.*reason=\([^ ]*\).*/\1/p' || true
    )"
    printf 'environment_preflight_blocked_%s' "${reason:-unknown}"
  elif [[ -f "$log_file" ]] && grep -q 'stage=api_request_exception' "$log_file"; then
    printf 'environment_model_provider_unreachable'
  elif [[ -f "$log_file" ]] && grep -q 'stage=api_http_error' "$log_file"; then
    printf 'environment_model_provider_http_error'
  elif [[ -f "$log_file" ]] && grep -q 'Empty response from LLM' "$log_file"; then
    printf 'model_empty_response'
  elif [[ "$terminal" != "NONE" && -n "$terminal" ]]; then
    printf '%s' "$terminal"
  else
    printf 'unknown_failure'
  fi
}

find_latest_run_dir() {
  local scenario_id="$1"
  find "$run_root" -maxdepth 1 -type d -name "*_${scenario_id}" 2>/dev/null | sort | tail -n 1
}

append_metrics() {
  local source_csv="$1"
  local target_csv="$2"
  if [[ ! -s "$source_csv" ]]; then
    return 0
  fi
  if [[ ! -s "$target_csv" ]]; then
    cat "$source_csv" >"$target_csv"
  else
    tail -n +2 "$source_csv" >>"$target_csv"
  fi
}

run_case_attempt() {
  local case_id="$1"
  local instruction="$2"
  local duration="$3"
  local target_package="$4"
  local prep="$5"
  local attempt="$6"
  local scenario_id="${case_id}_attempt${attempt}"
  local out_file="${suite_dir}/${scenario_id}.out"
  local code run_dir metrics report log_file status terminal category api_calls tool_calls total_tokens trace_integrity
  local benchmark_args selected_log_file

  wait_device || die "adb device not available"
  prep_state "$target_package" "$prep"

  benchmark_args=(
    --scenario-id "$scenario_id"
    --instruction "$instruction"
    --duration "$duration"
    --output-root "$run_root"
    --launch
    --auto-run
  )
  if [[ -n "$SERIAL" ]]; then
    benchmark_args+=(--serial "$SERIAL")
  fi

  set +e
  tools/run_device_benchmark.sh "${benchmark_args[@]}" >"$out_file" 2>&1 </dev/null
  code=$?
  set -e

  run_dir="$(sed -n 's/^Output directory: //p' "$out_file" | tail -n 1)"
  if [[ -z "$run_dir" ]]; then
    run_dir="$(find_latest_run_dir "$scenario_id")"
  fi
  metrics="$(sed -n 's/^  metrics : //p' "$out_file" | tail -n 1)"
  report="$(sed -n 's/^  report  : //p' "$out_file" | tail -n 1)"
  if [[ -z "$metrics" && -n "$run_dir" ]]; then
    metrics="${run_dir}/auto_metrics.csv"
  fi
  if [[ -z "$report" && -n "$run_dir" ]]; then
    report="${run_dir}/benchmark_report.md"
  fi
  log_file="${run_dir}/lynxflow.log"
  selected_log_file="${run_dir}/lynxflow.selected.log"

  if [[ -f "$metrics" ]]; then
    status="$(csv_field "$metrics" 4)"
    terminal="$(csv_field "$metrics" 5)"
    api_calls="$(csv_field "$metrics" 7)"
    tool_calls="$(csv_field "$metrics" 8)"
    total_tokens="$(csv_field "$metrics" 11)"
    trace_integrity="$(csv_field "$metrics" 28)"
    category="$(failure_category_for "$metrics" "$selected_log_file")"
    append_metrics "$metrics" "$all_metrics_csv"
  else
    status="missing_metrics"
    terminal="runner_error"
    api_calls="0"
    tool_calls="0"
    total_tokens="0"
    trace_integrity="missing"
    category="runner_error"
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$case_id" "$attempt" "$code" "$status" "$terminal" "$category" \
    "$api_calls" "$tool_calls" "$total_tokens" "$trace_integrity" "$run_dir" "$metrics" "$report" >>"$attempts_tsv"

  echo "CASE $case_id attempt=$attempt status=$status integrity=$trace_integrity category=$category run_dir=$run_dir"
  [[ "$code" -eq 0 && "$status" == "success" && "$trace_integrity" == "pass" ]]
}

run_case() {
  local case_id="$1"
  local instruction="$2"
  local duration="$3"
  local target_package="$4"
  local prep="$5"
  local max_attempts="$6"
  local attempt=1
  local final_line

  while [[ "$attempt" -le "$max_attempts" ]]; do
    if run_case_attempt "$case_id" "$instruction" "$duration" "$target_package" "$prep" "$attempt"; then
      break
    fi
    attempt=$((attempt + 1))
    if [[ "$attempt" -le "$max_attempts" ]]; then
      sleep 5
    fi
  done

  final_line="$(awk -F '\t' -v id="$case_id" '$1 == id { line = $0 } END { print line }' "$attempts_tsv")"
  printf '%s\n' "$final_line" >>"$final_tsv"
  local final_category
  final_category="$(printf '%s\n' "$final_line" | awk -F '\t' '{ print $6 }')"
  if [[ "$final_category" == environment_model_provider_* || "$final_category" == environment_preflight_blocked_* ]]; then
    consecutive_env_failures=$((consecutive_env_failures + 1))
  else
    consecutive_env_failures=0
  fi
  if [[ "$MAX_CONSECUTIVE_ENV_FAILURES" -gt 0 && "$consecutive_env_failures" -ge "$MAX_CONSECUTIVE_ENV_FAILURES" ]]; then
    abort_suite_for_environment=1
    printf 'aborted_after_%s_consecutive_environment_failures last_case=%s last_category=%s\n' \
      "$consecutive_env_failures" "$case_id" "$final_category" >"$abort_reason_file"
  fi
  local final_metrics
  final_metrics="$(printf '%s\n' "$final_line" | awk -F '\t' '{ print $12 }')"
  if [[ -f "$final_metrics" ]]; then
    append_metrics "$final_metrics" "$final_metrics_csv"
  fi
}

echo -e "case_id\tattempt\texit_code\tstatus\tterminal_code\tfailure_category\tapi_calls\ttool_calls\ttotal_tokens\ttrace_integrity\trun_dir\tmetrics\treport" >"$attempts_tsv"
echo -e "case_id\tattempt\texit_code\tstatus\tterminal_code\tfailure_category\tapi_calls\ttool_calls\ttotal_tokens\ttrace_integrity\trun_dir\tmetrics\treport" >"$final_tsv"

while IFS=$'\t' read -r case_id instruction duration target_package prep attempts; do
  [[ -n "${case_id:-}" ]] || continue
  [[ "$case_id" == \#* ]] && continue
  [[ "$case_id" == "id" ]] && continue
  [[ -n "${duration:-}" ]] || die "missing duration for case $case_id"
  [[ "$duration" =~ ^[0-9]+$ ]] || die "invalid duration for case $case_id: $duration"
  if [[ -z "${attempts:-}" ]]; then
    attempts="$DEFAULT_ATTEMPTS"
  fi
  [[ "$attempts" =~ ^[0-9]+$ ]] || die "invalid attempts for case $case_id: $attempts"
  run_case "$case_id" "$instruction" "$duration" "${target_package:-}" "${prep:-home}" "$attempts"
  if [[ "$abort_suite_for_environment" -eq 1 ]]; then
    echo "Suite stopped after consecutive environment failures. See: $abort_reason_file"
    break
  fi
done <"$SUITE_FILE"

total_cases="$(tail -n +2 "$final_tsv" | wc -l | tr -d ' ')"
final_success="$(tail -n +2 "$final_tsv" | awk -F '\t' '$4=="success" && $10=="pass"{n++} END{print n+0}')"
first_success="$(tail -n +2 "$attempts_tsv" | awk -F '\t' '$2=="1" && $4=="success" && $10=="pass"{n++} END{print n+0}')"
retry_success="$(tail -n +2 "$attempts_tsv" | awk -F '\t' '$2!="1" && $4=="success" && $10=="pass"{n++} END{print n+0}')"

{
  echo "# Benchmark Suite Report"
  echo
  echo "- Suite: ${SUITE_ID}"
  echo "- Suite file: ${SUITE_FILE}"
  echo "- Device: ${SERIAL:-default}"
  echo "- Final success: ${final_success} / ${total_cases}"
  echo "- First-attempt success: ${first_success} / ${total_cases}"
  echo "- Retry success count: ${retry_success}"
  if [[ -s "$abort_reason_file" ]]; then
    echo "- Suite abort reason: $(cat "$abort_reason_file")"
  fi
  echo "- Attempts TSV: ${attempts_tsv}"
  echo "- Final metrics CSV: ${final_metrics_csv}"
  echo "- All-attempt metrics CSV: ${all_metrics_csv}"
  echo
  echo "## Cases"
  echo
  echo "| Case | Final | Attempts | Integrity | Failure Category | API | Tools | Tokens | Report |"
  echo "| --- | --- | ---: | --- | --- | ---: | ---: | ---: | --- |"
  tail -n +2 "$final_tsv" | while IFS=$'\t' read -r case_id attempt code status terminal category api tools tokens integrity run_dir metrics report; do
    if [[ -n "$report" && -f "$report" ]]; then
      report_link="$report"
    else
      report_link="${run_dir:-missing}"
    fi
    echo "| ${case_id} | ${status} | ${attempt} | ${integrity} | ${category} | ${api} | ${tools} | ${tokens} | ${report_link} |"
  done
} >"$report_md"

echo "Suite completed."
echo "  suite_dir: $suite_dir"
echo "  report   : $report_md"
echo "  final    : $final_metrics_csv"
