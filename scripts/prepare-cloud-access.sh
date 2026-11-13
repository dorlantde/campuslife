#!/bin/sh
# New host only. Keep the initial root recovery login unchanged.
set -eu
[ "$(id -u)" = 0 ] || exit 1
if getent passwd campusdeploy >/dev/null; then
  echo 'campusdeploy already exists; inspect it instead of taking over an account.'
  exit 1
fi
[ -s /root/.ssh/authorized_keys ] || { echo 'Initial public-key configuration missing'; exit 1; }
getent group docker >/dev/null
useradd --create-home --user-group --shell /bin/bash campusdeploy
usermod -aG docker campusdeploy
install -d -m 0700 -o campusdeploy -g campusdeploy /home/campusdeploy/.ssh
install -m 0600 -o campusdeploy -g campusdeploy /root/.ssh/authorized_keys /home/campusdeploy/.ssh/authorized_keys
install -d -m 0750 -o campusdeploy -g campusdeploy /home/campusdeploy/campuslife
id campusdeploy
echo 'Deployment user created with Docker group access (host-root-equivalent); no sudoers or root SSH policy change.'
