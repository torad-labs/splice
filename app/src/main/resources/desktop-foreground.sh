# The local session-authenticated route reads the top-level ids; the overlay supplies the phase.
curl -q -s --noproxy '*' -m 1 -X POST -H "@$auth_file" \
  -H 'Content-Type: application/json' -H "X-Splice-Foreground-Owner: ${SPLICE_FOREGROUND_OWNER:-}" \
  --data-binary @- "$url?phase=$1" >/dev/null 2>&1
exit 0
