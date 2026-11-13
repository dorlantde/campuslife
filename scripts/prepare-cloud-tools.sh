#!/bin/sh
# Explicit new Ubuntu 22.04 host preparation. No application or database startup.
set -eu
[ "$(id -u)" = 0 ] || { echo 'Run through the initial root maintenance login'; exit 1; }
. /etc/os-release
[ "$ID" = ubuntu ] && [ "$VERSION_ID" = 22.04 ] && [ "$(dpkg --print-architecture)" = amd64 ] || exit 1
if command -v docker >/dev/null 2>&1; then
  echo 'Docker already exists; inspect the installation instead of silently replacing it.'
  exit 1
fi
for pkg in docker.io podman-docker containerd; do
  if dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed'; then
    echo "Conflicting package exists: $pkg; no automatic removal."
    exit 1
  fi
done
for file in /etc/apt/keyrings/campuslife-docker.asc /etc/apt/sources.list.d/campuslife-docker.sources; do
  [ ! -e "$file" ] || { echo "Setup file already exists: $file; inspect before resuming."; exit 1; }
done
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l
echo '== Refresh signed Ubuntu package indexes =='
apt-get -o DPkg::Lock::Timeout=60 -o Acquire::Retries=1 -o Acquire::http::Timeout=20 update
echo '== Install repository prerequisites =='
apt-get -o DPkg::Lock::Timeout=60 -o Acquire::Retries=1 --no-install-recommends install -y ca-certificates curl gnupg
echo '== Add Docker official signed repository =='
install -d -m 0755 /etc/apt/keyrings
task_key=$(mktemp)
trap 'rm -f "$task_key"' EXIT HUP INT TERM
curl -4 -fsSL --retry 2 --retry-delay 1 --retry-all-errors --connect-timeout 5 --max-time 15 \
  https://download.docker.com/linux/ubuntu/gpg -o "$task_key"
task_fpr=$(gpg --batch --show-keys --with-colons "$task_key" 2>/dev/null | awk -F: '$1=="fpr" {print $10; exit}')
[ "$task_fpr" = 9DC858229FC7DD38854AE2D88D81803C0EBFCD88 ] || { echo 'Docker signing key fingerprint differs'; exit 1; }
install -m 0644 "$task_key" /etc/apt/keyrings/campuslife-docker.asc
cat > /etc/apt/sources.list.d/campuslife-docker.sources <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: jammy
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/campuslife-docker.asc
EOF
apt-get -o DPkg::Lock::Timeout=60 -o Acquire::Retries=1 -o Acquire::https::Timeout=20 update
echo '== Candidate versions =='
apt-cache policy openjdk-21-jdk-headless maven docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
echo '== Install JDK21 Maven Docker Engine and Compose =='
apt-get -o DPkg::Lock::Timeout=60 -o Acquire::Retries=1 --no-install-recommends install -y \
  openjdk-21-jdk-headless maven docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
echo '== Verify installed tools (no hello-world pull or application startup) =='
java --version
javac --version
mvn --version
keytool -help >/dev/null 2>&1
docker version
docker compose version
docker buildx version
docker info --format 'CPUs={{.NCPU}} MemoryBytes={{.MemTotal}} Cgroup={{.CgroupVersion}} Driver={{.Driver}} Root={{.DockerRootDir}} Security={{json .SecurityOptions}}'
dpkg-query -W -f='${Package} ${Version}\n' openjdk-21-jdk-headless maven docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
echo '== Tool installation complete; no business services created =='
