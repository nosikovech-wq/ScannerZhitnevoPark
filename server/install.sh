#!/bin/bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
  echo "Запустите от root: sudo bash install.sh"
  exit 1
fi

apt-get update
apt-get install -y python3 python3-venv python3-pip

id zhitnevo >/dev/null 2>&1 || useradd --system --home /var/lib/zhitnevo --shell /usr/sbin/nologin zhitnevo
mkdir -p /opt/zhitnevo /var/lib/zhitnevo/photos
cp app.py requirements.txt /opt/zhitnevo/
if [ ! -f /var/lib/zhitnevo/fleet.csv ]; then
  cp fleet.csv /var/lib/zhitnevo/fleet.csv
fi
chown -R zhitnevo:zhitnevo /var/lib/zhitnevo /opt/zhitnevo

python3 -m venv /opt/zhitnevo/venv
/opt/zhitnevo/venv/bin/pip install --upgrade pip
/opt/zhitnevo/venv/bin/pip install -r /opt/zhitnevo/requirements.txt

if [ ! -f /etc/zhitnevo.env ]; then
  PASS="$(openssl rand -base64 12 | tr -d '/+=' | cut -c1-12)"
  cat > /etc/zhitnevo.env <<EOF
DATA_DIR=/var/lib/zhitnevo
ADMIN_USER=admin
ADMIN_PASSWORD=${PASS}
PORT=8787
EOF
  chmod 600 /etc/zhitnevo.env
  echo "Создан администратор admin / ${PASS}"
  echo "Пароль записан в /etc/zhitnevo.env"
else
  echo "Файл /etc/zhitnevo.env уже есть, пароль не менялся"
fi

cp zhitnevo.service /etc/systemd/system/zhitnevo.service
systemctl daemon-reload
systemctl enable zhitnevo
systemctl restart zhitnevo
sleep 1
systemctl --no-pager --lines=20 status zhitnevo || true
echo
echo "Сервер: http://$(hostname -I | awk '{print $1}'):8787/api/health"
echo "Откройте порт, если он закрыт: ufw allow 8787/tcp"
