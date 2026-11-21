# Shared env setup for the case scripts; sourced from the candidate checkout. It changes nothing about the product.
# A JDK whose security files are complete (a sandbox that lacks /etc/java-*), when one was prepared at /tmp/jdk.
[ -x /tmp/jdk/bin/java ] && PATH=/tmp/jdk/bin:$PATH && export PATH
[ -f /tmp/openssl.cnf ] && [ -z "$OPENSSL_CONF" ] && export OPENSSL_CONF=/tmp/openssl.cnf
# The harness builds the jar from this checkout, so the case judges this tree's build, never a jar left from another.
true
