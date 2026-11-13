#!/bin/sh
exec curl --fail --silent --max-time 2 http://127.0.0.1:8080/api/v1/system/health >/dev/null
