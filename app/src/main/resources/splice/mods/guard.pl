# The session leader is the only process that ever signals this group. While it
# is running, the kernel cannot give its group ID to anybody else.
use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use Config;
use POSIX qw(setsid WNOHANG);
use Time::HiRes qw(clock_gettime CLOCK_MONOTONIC sleep);

my ($parent, $grace, @command) = @ARGV;
defined(setsid()) && getpgrp(0) == $$ && getppid() == $parent or exit 125;
my $stopping = 0;
$SIG{TERM} = sub { $stopping = 1 };
$SIG{PIPE} = 'IGNORE';

# The JVM has to publish this guardian's identity before any host can run.
sysread(STDIN, my $admission, 1) == 1 && !$stopping && getppid() == $parent or exit 125;

# A Linux subreaper adopts orphaned helpers: ECHILD is proof all descendants
# were reaped, not an inference from a process-table snapshot.
my $prctl = $Config{archname} =~ /^x86_64-linux/ ? 157
    : $Config{archname} =~ /^aarch64-linux/ ? 167 : undef;
my $adopted = defined($prctl) && syscall($prctl, 36, 1, 0, 0, 0) == 0;

my $host = fork();
defined($host) or exit 125;
if (!$host) {
    $SIG{TERM} = 'DEFAULT';
    $SIG{PIPE} = 'DEFAULT';
    exec {$command[0]} @command or exit 127;
}
close STDIN;
close STDERR;

my $deadline;
my $code = 137;
while (!$stopping) {
    if (waitpid($host, WNOHANG) == $host) {
        $code = ($? & 127) ? 128 + ($? & 127) : $? >> 8;
        last;
    }
    if (getppid() != $parent) {
        # Parent identity is the relationship maintained by the kernel, not a PID lookup.
        $deadline //= clock_gettime(CLOCK_MONOTONIC) + $grace;
        last if clock_gettime(CLOCK_MONOTONIC) >= $deadline;
    }
    sleep(0.05);
}

sub drained {
    my $until = clock_gettime(CLOCK_MONOTONIC) + 0.6;
    do {
        my $child;
        do { $child = waitpid(-1, WNOHANG); } while ($child > 0);
        return 1 if $child < 0 && $!{ECHILD};
        sleep(0.025);
    } while (clock_gettime(CLOCK_MONOTONIC) < $until);
    return 0;
}

# Linux pidfds pin the exact processes we inspect. Check membership after opening
# the fd; if the pid has already turned over the old fd cannot signal the new one.
sub kill_members {
    for my $stat (glob('/proc/[0-9]*/stat')) {
        my ($pid) = $stat =~ m{/proc/(\d+)/stat};
        next if !$pid || $pid == $$;
        my $fd = syscall(434, $pid, 0); # pidfd_open on both supported Linux architectures
        next if $fd < 0;
        if (open(my $process, '<', $stat)) {
            my $line = do { local $/; <$process> };
            if (defined($line)) {
                my @fields = split ' ', substr($line, rindex($line, ')') + 1);
                if (defined($fields[2]) && $fields[2] eq "$$") {
                    syscall(424, $fd, 9, 0, 0); # pidfd_send_signal(SIGKILL)
                }
            }
        }
        POSIX::close($fd);
    }
}

# Any group signal is sent from its own live session leader, not via a saved id.
kill 'TERM', -$$;
if ($adopted) {
    exit $code if drained();
    kill_members();
    exit $code if drained();
}

# Preserve the host's exit reason without letting a full pipe prevent cleanup.
my $flags = fcntl(STDOUT, F_GETFL, 0);
if (defined($flags) && fcntl(STDOUT, F_SETFL, $flags | O_NONBLOCK)) {
    syswrite(STDOUT, "{\"type\":\"host-exit\",\"code\":$code}\n");
}
# One kernel operation ends the group, including this leader. A successor still
# checks the process table for members before allowing another run.
kill 'KILL', -$$;
exit 125;
