# The daemon accepts only opaque ids and phase. Tool inputs and responses stay here.
body=$(/usr/bin/perl -MJSON::PP -0777 -ne '
  my $event = decode_json($_);
  my $phase = {PreToolUse => "start", PostToolUse => "end"}->{$event->{hook_event_name}} or exit 1;
  print encode_json({session_id => $event->{session_id}, tool_use_id => $event->{tool_use_id}, phase => $phase});
' 2>/dev/null) || exit 0
printf '%s' "$body" | curl -q -s --noproxy '*' -m 1 -X POST -H "@$auth_file" \
  -H 'Content-Type: application/json' -H "X-Splice-Foreground-Owner: ${SPLICE_FOREGROUND_OWNER:-}" \
  --data-binary @- "$url" >/dev/null 2>&1
exit 0
