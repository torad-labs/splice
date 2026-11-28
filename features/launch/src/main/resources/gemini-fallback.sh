# A host that kept the replaced executable can pass it at the translator boundary.
case "${1-}" in
  --stands-in-for=/*) original=${1#*=}; shift; exec "$original" "$@" ;;
  --stands-in-for) original=$2; shift 2; exec "$original" "$@" ;;
esac

# Do not resolve back to our shim (even via a symlink) or run splice's terminal gateway recipe.
try_claude() {
  candidate=$1
  shift
  [ -f "$candidate" ] && [ -x "$candidate" ] && ! [ "$candidate" -ef "$0" ] || return
  if [ "$candidate" -ef "$splice_launcher" ]; then
    candidate=$unwrapped
    [ -f "$candidate" ] && [ -x "$candidate" ] && ! [ "$candidate" -ef "$splice_launcher" ] &&
      ! [ "$candidate" -ef "$0" ] || return
  fi
  exec "$candidate" "$@"
}
rest=${PATH-}:
while [ -n "$rest" ]; do
  dir=${rest%%:*}
  rest=${rest#*:}
  case $dir in /*) try_claude "$dir/claude" "$@" ;; esac
done
# Desktop may omit the user's bin directory from PATH.
try_claude "$local_claude" "$@"
printf '%s\n' 'splice: the Gemini shim could not find the Claude Code executable it replaces' >&2
exit 127
