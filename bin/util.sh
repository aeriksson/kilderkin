# Shell utils.
set -o errexit
set -o nounset
set -o pipefail
[[ "${TRACE:-}" ]] && set -o xtrace

declare root_path="$(basename $(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd))"
declare script_name="$(basename "$0")"

ansi_wrap() { printf "\e[0;$1m${@:2}\e[0m\n"; }
bold()      { ansi_wrap 1  "$@"; }
italic()    { ansi_wrap 3  "$@"; }
underline() { ansi_wrap 4  "$@"; }
black()     { ansi_wrap 30 "$@"; }
red()       { ansi_wrap 31 "$@"; }
green()     { ansi_wrap 32 "$@"; }
yellow()    { ansi_wrap 33 "$@"; }
blue()      { ansi_wrap 34 "$@"; }
magenta()   { ansi_wrap 35 "$@"; }
cyan()      { ansi_wrap 36 "$@"; }
white()     { ansi_wrap 37 "$@"; }
log_line()  { printf "$(date -u +"%Y-%m-%dT%H:%M:%S.000Z") - $1 - $(blue bin/$script_name) - $2\n" >&2; }
_lvl_ord()  { case ${1:-} in trace) echo 0;; debug) echo 1;; warning) echo 3;; error) echo 4;; *) echo 2;; esac; }
_log_at()   { (( $(_lvl_ord $1) >= $(_lvl_ord ${LOG_LEVEL:-}) )); }
log_debug() { _log_at debug && log_line "$(magenta "DEBUG")" "$@"; }
log_info()  { _log_at info  && log_line "$(white   " INFO")" "$@"; }
log_warn()  { _log_at warn  && log_line "$(yellow  " WARN")" "$@"; }
log_error() { _log_at error && log_line "$(red     "ERROR")" "$@"; }
fail()      { log_error "$@"; exit 1; }
run_trace() { local args=( "$@" ); log_info "Running '$(cyan "$(printf '%s\n' "${args[@]}" | sed 's/"/\\"/g' | sed "s|^$root_path/||g" | sed "s/\(.* .*\)/\"\1\"/g" | paste -s -d " " -)")'..."; eval $(printf '%s\n' "${args[@]}" | sed 's/"/\\"/g' | sed "s/\(.* .*\)/\"\1\"/g" | paste -s -d " " -); }
