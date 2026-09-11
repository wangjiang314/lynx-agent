#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.juwan.lynx"
REMOTE_SCREENSHOT="cache/screenshot.jpg"
ADB_BIN="${ADB_BIN:-adb}"
DEVICE_SERIAL=""
DURATION_SEC=60
FPS=2
OUTPUT_ROOT="artifacts/internal-recordings"
SCENARIO_ID="manual"
MAKE_GIF=1

usage() {
  cat <<'EOF'
Usage:
  tools/record_internal_screenshots.sh [options]

Records the screenshots already captured by Lynx Agent's MediaProjection service.
This avoids competing with Lynx for screen capture and does not require Android's
/system/bin/screenrecord command.

Options:
  --serial SERIAL         adb device serial.
  --duration SEC          Capture duration. Default: 60.
  --fps FPS               Pull frequency and output frame rate. Default: 2.
  --scenario-id ID        Used in output naming. Default: manual.
  --output-root DIR       Output directory root. Default: artifacts/internal-recordings.
  --no-gif                Only generate MP4.
  --help                  Show this message.

Environment:
  ADB_BIN                 adb binary path. Default: adb.

Examples:
  ADB_BIN=/path/to/adb tools/record_internal_screenshots.sh \
    --serial MQS0219531003849 \
    --scenario-id settings_wlan \
    --duration 45 \
    --fps 2
EOF
}

die() {
  echo "Error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      DEVICE_SERIAL="${2:-}"
      shift 2
      ;;
    --duration)
      DURATION_SEC="${2:-}"
      shift 2
      ;;
    --fps)
      FPS="${2:-}"
      shift 2
      ;;
    --scenario-id)
      SCENARIO_ID="${2:-}"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="${2:-}"
      shift 2
      ;;
    --no-gif)
      MAKE_GIF=0
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

[[ "$DURATION_SEC" =~ ^[0-9]+$ ]] || die "--duration must be an integer"
[[ "$FPS" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--fps must be numeric"
command -v "$ADB_BIN" >/dev/null 2>&1 || die "adb not found: $ADB_BIN"
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg is required to assemble video"

adb_base=("$ADB_BIN")
if [[ -n "$DEVICE_SERIAL" ]]; then
  adb_base+=(-s "$DEVICE_SERIAL")
fi

sanitized_scenario="$(printf '%s' "$SCENARIO_ID" | tr '[:space:]/' '__' | tr -cd '[:alnum:]_-.')"
[[ -n "$sanitized_scenario" ]] || sanitized_scenario="manual"
timestamp="$(date +%Y%m%d_%H%M%S)"
run_dir="$OUTPUT_ROOT/${timestamp}_${sanitized_scenario}"
frames_dir="$run_dir/frames"
mkdir -p "$frames_dir"

interval="$(awk -v fps="$FPS" 'BEGIN { if (fps <= 0) exit 1; printf "%.3f", 1 / fps }')" || die "invalid --fps"
frame_count="$(awk -v duration="$DURATION_SEC" -v fps="$FPS" 'BEGIN { printf "%d", duration * fps }')"
[[ "$frame_count" -gt 0 ]] || die "duration and fps produce zero frames"

last_hash=""
kept=0
for i in $(seq 1 "$frame_count"); do
  target="$frames_dir/frame_$(printf '%05d' "$i").jpg"
  if "${adb_base[@]}" exec-out run-as "$PACKAGE_NAME" cat "$REMOTE_SCREENSHOT" >"$target" 2>/dev/null; then
    if [[ -s "$target" ]]; then
      current_hash="$(shasum -a 256 "$target" | awk '{print $1}')"
      # Keep duplicate frames too so timing remains faithful, but count unique changes.
      if [[ "$current_hash" != "$last_hash" ]]; then
        kept=$((kept + 1))
        last_hash="$current_hash"
      fi
    else
      rm -f "$target"
    fi
  else
    rm -f "$target"
  fi
  sleep "$interval"
done

first_frame="$(find "$frames_dir" -name 'frame_*.jpg' -type f | sort | head -n1 || true)"
[[ -n "$first_frame" ]] || die "no screenshots captured; is Lynx installed as a debuggable build and has it captured at least one frame?"

mp4="$run_dir/${sanitized_scenario}.mp4"
gif="$run_dir/${sanitized_scenario}.gif"
ffmpeg -y -framerate "$FPS" -pattern_type glob -i "$frames_dir/frame_*.jpg" \
  -vf "scale=540:-2,fps=20" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$mp4" >/dev/null 2>&1

gif_output=""
if [[ "$MAKE_GIF" -eq 1 ]]; then
  ffmpeg -y -i "$mp4" -vf "fps=10,scale=420:-1:flags=lanczos" "$gif" >/dev/null 2>&1
  gif_output="$gif"
fi

captured_count="$(find "$frames_dir" -name 'frame_*.jpg' -type f | wc -l | tr -d ' ')"
cat <<EOF
RUN_DIR=$run_dir
FRAMES_DIR=$frames_dir
MP4=$mp4
GIF=$gif_output
FRAME_COUNT=$captured_count
UNIQUE_FRAME_CHANGES=$kept
EOF
