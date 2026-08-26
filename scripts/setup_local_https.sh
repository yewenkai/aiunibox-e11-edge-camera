#!/usr/bin/env bash
set -euo pipefail

e11_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
e11_repo_root="$(cd "$e11_script_dir/.." && pwd)"
e11_output_dir="$e11_repo_root/device-data/https"
e11_package="org.e11camera.edge"
e11_remote_dir="/data/local/tmp/e11-https"
e11_adb=(adb)

if [[ -n "${E11_ADB_SERIAL:-}" ]]; then
  e11_adb+=(-s "$E11_ADB_SERIAL")
fi

e11_device_ip="${1:-}"
if [[ -z "$e11_device_ip" ]]; then
  e11_device_ip="$("${e11_adb[@]}" shell ip -4 -o addr show wlan0 | tr -d '\r' | awk '{print $4}' | cut -d/ -f1 | head -1)"
fi
if [[ ! "$e11_device_ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
  echo "无法确定设备 IPv4 地址，可手动执行：$0 192.168.x.x" >&2
  exit 1
fi

umask 077
mkdir -p "$e11_output_dir"
e11_ca_key="$e11_output_dir/local-ca.key"
e11_ca_cert="$e11_output_dir/local-ca.crt"
e11_password_file="$e11_output_dir/password.txt"
e11_server_p12="$e11_output_dir/server.p12"

if [[ -e "$e11_ca_key" && ! -e "$e11_ca_cert" ]] ||
   [[ ! -e "$e11_ca_key" && -e "$e11_ca_cert" ]]; then
  echo "本地 CA 文件不完整，请人工处理 $e11_output_dir 后重试，脚本不会覆盖残留私钥。" >&2
  exit 1
fi

if [[ ! -e "$e11_ca_key" ]]; then
  openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
    -subj "/CN=AIUniBOX-E11 Local Root CA/O=E11 Home Lab" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" \
    -keyout "$e11_ca_key" -out "$e11_ca_cert"
  echo "已生成仅保存在本机的 AIUniBOX-E11 根 CA。"
else
  echo "复用已有根 CA，iPhone 无需重复安装信任。"
fi

e11_temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/e11-https.XXXXXX")"
trap 'rm -rf "$e11_temp_dir"' EXIT
e11_server_key="$e11_temp_dir/server.key"
e11_server_csr="$e11_temp_dir/server.csr"
e11_server_cert="$e11_temp_dir/server.crt"
e11_extensions="$e11_temp_dir/server-ext.cnf"

printf '%s\n' \
  '[server_ext]' \
  'basicConstraints=critical,CA:FALSE' \
  'keyUsage=critical,digitalSignature,keyEncipherment' \
  'extendedKeyUsage=serverAuth' \
  'subjectKeyIdentifier=hash' \
  'authorityKeyIdentifier=keyid,issuer' \
  'subjectAltName=@alt_names' \
  '[alt_names]' \
  "IP.1=$e11_device_ip" \
  'IP.2=127.0.0.1' \
  'DNS.1=localhost' \
  'DNS.2=aiunibox-e11.local' > "$e11_extensions"

openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -subj "/CN=$e11_device_ip/O=E11 Home Lab" \
  -keyout "$e11_server_key" -out "$e11_server_csr"
openssl x509 -req -sha256 -days 397 \
  -in "$e11_server_csr" \
  -CA "$e11_ca_cert" -CAkey "$e11_ca_key" -CAcreateserial \
  -extfile "$e11_extensions" -extensions server_ext \
  -out "$e11_server_cert"
openssl rand -hex 24 > "$e11_password_file"
openssl pkcs12 -export -name e11-local-https \
  -inkey "$e11_server_key" -in "$e11_server_cert" -certfile "$e11_ca_cert" \
  -passout "file:$e11_password_file" -out "$e11_server_p12"

"${e11_adb[@]}" shell mkdir -p "$e11_remote_dir"
"${e11_adb[@]}" push "$e11_server_p12" "$e11_remote_dir/server.p12" >/dev/null
"${e11_adb[@]}" push "$e11_password_file" "$e11_remote_dir/password.txt" >/dev/null
"${e11_adb[@]}" push "$e11_ca_cert" "$e11_remote_dir/local-ca.crt" >/dev/null
"${e11_adb[@]}" shell chmod 0644 \
  "$e11_remote_dir/server.p12" "$e11_remote_dir/password.txt" "$e11_remote_dir/local-ca.crt"
"${e11_adb[@]}" shell run-as "$e11_package" mkdir -p files/tls
"${e11_adb[@]}" shell run-as "$e11_package" cp "$e11_remote_dir/server.p12" files/tls/server.p12
"${e11_adb[@]}" shell run-as "$e11_package" cp "$e11_remote_dir/password.txt" files/tls/password.txt
"${e11_adb[@]}" shell run-as "$e11_package" cp "$e11_remote_dir/local-ca.crt" files/tls/local-ca.crt
"${e11_adb[@]}" shell run-as "$e11_package" chmod 0600 files/tls/server.p12 files/tls/password.txt
"${e11_adb[@]}" shell run-as "$e11_package" chmod 0644 files/tls/local-ca.crt
"${e11_adb[@]}" shell rm -f \
  "$e11_remote_dir/server.p12" "$e11_remote_dir/password.txt" "$e11_remote_dir/local-ca.crt"

"${e11_adb[@]}" shell am force-stop "$e11_package"
"${e11_adb[@]}" shell monkey -p "$e11_package" 1 >/dev/null

echo
echo "HTTPS 证书已部署并重启应用："
echo "  控制页：https://$e11_device_ip:8443/"
echo "  iPhone 根证书：http://$e11_device_ip:8080/local-ca.crt"
echo "  本机根证书：$e11_ca_cert"
openssl x509 -in "$e11_server_cert" -noout -dates -fingerprint -sha256
