#!/bin/sh
set -eu
umask 077
# Never generate a new cookie over an existing broker identity.
if [ -f /var/lib/rabbitmq/.erlang.cookie ]; then
    cmp -s /run/secrets/RABBIT_ERLANG_COOKIE /var/lib/rabbitmq/.erlang.cookie || {
        echo 'Broker cookie does not match the existing owned volume' >&2; exit 1;
    }
else
    cp /run/secrets/RABBIT_ERLANG_COOKIE /var/lib/rabbitmq/.erlang.cookie
    chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie
    chmod 0400 /var/lib/rabbitmq/.erlang.cookie
fi
exec /usr/local/bin/docker-entrypoint.sh rabbitmq-server
