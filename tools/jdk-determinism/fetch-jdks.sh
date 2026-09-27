#!/usr/bin/env bash
# Fetch the Linux x64 JDK runtimes of the JDK determinism sweep from their vendors' official download endpoints,
# verify the vendor checksum where one is published, extract each to $JDKS/<id>/ and delete the tarball.
# Writes $JDKS/manifest.tsv: id, vendor, jvm, exact java.runtime.version / java.vm.name, url, sha256.
#
# Usage: fetch-jdks.sh [id ...]      (no ids: every runtime of the table below; an installed id is skipped)
# Environment: JDKS (default: ../../../jdks relative to the worktree = the session scratchpad's jdks/)
#
# Resolvers (each prints "url checksum algo"; checksum may be "-" when the vendor publishes none):
#   adoptium_latest <feature>            api.adoptium.net latest GA HotSpot (Eclipse Temurin)
#   adoptium_version <x.y.z>             api.adoptium.net version range [x.y.z, x.y.z.99]
#   zulu <feature>                       api.azul.com metadata (CA, glibc, no CRaC/FX)
#   corretto <feature>                   corretto.aws latest + latest_sha256
#   microsoft <feature>                  aka.ms/download-jdk + .sha256sum.txt
#   liberica <feature>                   api.bell-sw.com (sha1)
#   url <url> <checksum-url|->           a fixed vendor URL (jdk.java.net, download.oracle.com, GitHub releases)
#   foojay <distro> <version> <regex>    api.foojay.io disco index -> the vendor's own direct_download_uri
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JDKS=${JDKS:-/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/jdks}
mkdir -p "$JDKS"
MANIFEST=$JDKS/manifest.tsv
[ -f "$MANIFEST" ] || printf 'id\tvendor\tjvm\tversion\tvm\turl\tsha256\n' > "$MANIFEST"
C="curl -fsSL --retry 5 --retry-delay 3 --retry-all-errors"
SEMERU=https://github.com/ibmruntimes

# id | vendor | resolver and arguments
RUNTIMES=(
  "temurin-21.0.2|Eclipse Temurin|adoptium_version 21.0.2"
  "temurin-21.0.5|Eclipse Temurin|adoptium_version 21.0.5"
  "temurin-21.0.8|Eclipse Temurin|adoptium_version 21.0.8"
  "temurin-21.0.10|Eclipse Temurin|adoptium_version 21.0.10"
  "temurin-21.0.11|Eclipse Temurin|adoptium_version 21.0.11"
  "temurin-21-latest|Eclipse Temurin|adoptium_latest 21"
  "temurin-22|Eclipse Temurin|adoptium_latest 22"
  "temurin-23|Eclipse Temurin|adoptium_latest 23"
  "temurin-24|Eclipse Temurin|adoptium_latest 24"
  "temurin-25|Eclipse Temurin|adoptium_latest 25"
  "temurin-26|Eclipse Temurin|adoptium_latest 26"
  "temurin-27|Eclipse Temurin|adoptium_latest 27"
  "zulu-21|Azul Zulu|zulu 21"
  "zulu-25|Azul Zulu|zulu 25"
  "corretto-21|Amazon Corretto|corretto 21"
  "corretto-25|Amazon Corretto|corretto 25"
  "microsoft-21|Microsoft Build of OpenJDK|microsoft 21"
  "microsoft-25|Microsoft Build of OpenJDK|microsoft 25"
  "liberica-21|BellSoft Liberica|liberica 21"
  "liberica-25|BellSoft Liberica|liberica 25"
  "oracle-openjdk-21|Oracle OpenJDK (jdk.java.net)|url https://download.java.net/java/GA/jdk21/fd2272bbf8e04c3dbaee13770090416c/35/GPL/openjdk-21_linux-x64_bin.tar.gz https://download.java.net/java/GA/jdk21/fd2272bbf8e04c3dbaee13770090416c/35/GPL/openjdk-21_linux-x64_bin.tar.gz.sha256"
  "oracle-openjdk-21.0.2|Oracle OpenJDK (jdk.java.net)|foojay oracle_open_jdk 21.0.2 ^openjdk-21\.0\.2_linux-x64_bin\.tar\.gz$"
  "oracle-openjdk-25|Oracle OpenJDK (jdk.java.net)|foojay oracle_open_jdk 25 ^openjdk-25[0-9.]*_linux-x64_bin\.tar\.gz$"
  "oracle-jdk-21|Oracle JDK|url https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz.sha256"
  "oracle-jdk-25|Oracle JDK|url https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz.sha256"
  "graalvm-ce-21|GraalVM Community|url https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz.sha256"
  "graalvm-ce-25|GraalVM Community|foojay graalvm_community 25 ^graalvm-community-jdk-25[0-9.i-]*_linux-x64_bin\.tar\.gz$"
  "oracle-graalvm-21|Oracle GraalVM|url https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-x64_bin.tar.gz https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-x64_bin.tar.gz.sha256"
  "oracle-graalvm-25|Oracle GraalVM|url https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz.sha256"
  "semeru-21.0.5|IBM Semeru Open Edition|url $SEMERU/semeru21-binaries/releases/download/jdk-21.0.5%2B11_openj9-0.48.0/ibm-semeru-open-jdk_x64_linux_21.0.5_11_openj9-0.48.0.tar.gz $SEMERU/semeru21-binaries/releases/download/jdk-21.0.5%2B11_openj9-0.48.0/ibm-semeru-open-jdk_x64_linux_21.0.5_11_openj9-0.48.0.tar.gz.sha256.txt"
  "semeru-21.0.10|IBM Semeru Open Edition|url $SEMERU/semeru21-binaries/releases/download/jdk-21.0.10%2B7_openj9-0.57.0/ibm-semeru-open-jdk_x64_linux_21.0.10_7_openj9-0.57.0.tar.gz $SEMERU/semeru21-binaries/releases/download/jdk-21.0.10%2B7_openj9-0.57.0/ibm-semeru-open-jdk_x64_linux_21.0.10_7_openj9-0.57.0.tar.gz.sha256.txt"
  "semeru-25.0.2|IBM Semeru Open Edition|url $SEMERU/semeru25-binaries/releases/download/jdk-25.0.2%2B10_openj9-0.57.0/ibm-semeru-open-jdk_x64_linux_25.0.2_10_openj9-0.57.0.tar.gz $SEMERU/semeru25-binaries/releases/download/jdk-25.0.2%2B10_openj9-0.57.0/ibm-semeru-open-jdk_x64_linux_25.0.2_10_openj9-0.57.0.tar.gz.sha256.txt"
  "sapmachine-21|SAP SapMachine|foojay sap_machine 21 ^sapmachine-jdk-21[0-9.]*_linux-x64_bin\.tar\.gz$"
  "dragonwell-21|Alibaba Dragonwell|foojay dragonwell 21 ^Alibaba_Dragonwell_Standard_21[0-9.]*_x64_linux\.tar\.gz$"
  "kona-21|Tencent Kona|foojay kona 21 ^TencentKona-21[0-9.b]*-jdk_linux-x86_64\.tar\.gz$"
)

adoptium_pick() { python3 -c '
import json,sys
d=json.load(sys.stdin)
for a in d:
    b=a["binaries"][0] if "binaries" in a else a["binary"]
    print(b["package"]["link"], b["package"]["checksum"], "sha256"); break'; }
adoptium_latest() { $C "https://api.adoptium.net/v3/assets/latest/$1/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" | adoptium_pick; }
adoptium_version() { $C "https://api.adoptium.net/v3/assets/version/%5B$1%2C$1.99%5D?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=linux&page_size=5&project=jdk&release_type=ga&sort_method=DATE&sort_order=DESC&vendor=eclipse" | adoptium_pick; }
zulu() {
  local uuid url
  read -r uuid url < <($C "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$1&os=linux&arch=x64&archive_type=tar.gz&java_package_type=jdk&latest=true&release_status=ga&crac_supported=false&javafx_bundled=false&availability_types=CA" \
    | python3 -c 'import json,sys; d=[p for p in json.load(sys.stdin) if "musl" not in p["name"]]; print(d[0]["package_uuid"], d[0]["download_url"])')
  echo "$url $($C "https://api.azul.com/metadata/v1/zulu/packages/$uuid" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha256_hash"])') sha256"
}
corretto() { echo "https://corretto.aws/downloads/latest/amazon-corretto-$1-x64-linux-jdk.tar.gz $($C https://corretto.aws/downloads/latest_sha256/amazon-corretto-$1-x64-linux-jdk.tar.gz) sha256"; }
microsoft() { local u="https://aka.ms/download-jdk/microsoft-jdk-$1-linux-x64.tar.gz"; echo "$u $($C "$u.sha256sum.txt" | cut -d' ' -f1) sha256"; }
liberica() { $C "https://api.bell-sw.com/v1/liberica/releases?version-feature=$1&os=linux&arch=x86&bitness=64&package-type=tar.gz&bundle-type=jdk&version-modifier=latest" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["downloadUrl"], d[0]["sha1"], "sha1")'; }
url() { local sum="-"; [ "$2" != - ] && sum=$($C "$2" | tr -s ' \t' ' ' | cut -d' ' -f1); echo "$1 $sum sha256"; }
foojay() {
  local id; id=$($C "https://api.foojay.io/disco/v3.0/packages?version=$2&distribution=$1&architecture=x64&archive_type=tar.gz&package_type=jdk&operating_system=linux&lib_c_type=glibc&latest=available" \
    | python3 -c 'import json,re,sys; rx=re.compile(sys.argv[1]); print(next(p["id"] for p in json.load(sys.stdin)["result"] if rx.search(p["filename"])))' "$3") || return 1
  $C "https://api.foojay.io/disco/v3.0/ids/$id" | python3 -c '
import json,sys,subprocess
p=json.load(sys.stdin)["result"][0]
s=p.get("checksum") or "-"
if s=="-" and p.get("checksum_uri"):
    s=subprocess.run(["curl","-fsSL","--retry","5",p["checksum_uri"]],capture_output=True,text=True).stdout.split()[0]
import re
t=(p.get("checksum_type") or "sha256").lower()
if not re.fullmatch(r"[0-9a-fA-F]{32,128}", s) or t not in ("sha256","sha1","md5"): s="-"
print(p["direct_download_uri"], s.lower(), t)'
}

install() {
  local id=$1 vendor=$2 resolver=$3 dir=$JDKS/$1 url sum algo got tarball
  [ -x "$dir/bin/java" ] && { echo "have    $id"; return 0; }
  read -r url sum algo < <(eval "$resolver") || { echo "FAILED  $id: resolver ($resolver)"; return 1; }
  [ -n "${url:-}" ] || { echo "FAILED  $id: no url"; return 1; }
  tarball=$JDKS/$id.tar.gz
  $C -o "$tarball" "$url" || { echo "FAILED  $id: download $url"; rm -f "$tarball"; return 1; }
  if [ "$sum" != - ]; then
    got=$(${algo}sum "$tarball" | cut -d' ' -f1)
    [ "$got" = "$sum" ] || { echo "FAILED  $id: $algo $got, vendor says $sum"; rm -f "$tarball"; return 1; }
  fi
  local sha; sha=$(sha256sum "$tarball" | cut -d' ' -f1)
  rm -rf "$dir"; mkdir -p "$dir"
  tar -xzf "$tarball" -C "$dir" --strip-components=1 || { echo "FAILED  $id: extract"; return 1; }
  rm -f "$tarball"
  [ -x "$dir/bin/java" ] || { echo "FAILED  $id: no bin/java after extraction"; return 1; }
  local props ver vm jvm
  props=$(env -u JAVA_TOOL_OPTIONS "$dir/bin/java" -XshowSettings:properties -version 2>&1)
  ver=$(sed -n 's/^ *java.runtime.version = //p' <<<"$props"); vm="$(sed -n 's/^ *java.vm.name = //p' <<<"$props") / $(sed -n 's/^ *java.vendor.version = //p' <<<"$props")"
  case "$vm" in *OpenJ9*) jvm=OpenJ9 ;; *GraalVM*|*Graal*) jvm="HotSpot+Graal JIT" ;; *) jvm=HotSpot ;; esac
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$vendor" "$jvm" "$ver" "$vm" "$url" "$sha" >> "$MANIFEST"
  echo "ok      $id  $ver  ($vm)  vendor-checksum=${sum:0:12}.. ($algo)"
}

want=("$@")
for row in "${RUNTIMES[@]}"; do
  IFS='|' read -r id vendor resolver <<<"$row"
  if [ ${#want[@]} -gt 0 ]; then printf '%s\n' "${want[@]}" | grep -Fxq "$id" || continue; fi
  install "$id" "$vendor" "$resolver"
done
