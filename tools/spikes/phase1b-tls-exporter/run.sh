#!/usr/bin/env bash
# RideLink Phase 1b security spike — TLS keying-material exporter interoperability.
#
# Answers the two questions ADR-007 Amendment A1 says must be answered before the rest of
# Phase 1b is built on them:
#
#   1. Is a TLS 1.3 keying-material exporter reachable from PUBLIC API on both platforms?
#   2. For the SAME TLS 1.3 connection, do an Apple endpoint and an Android endpoint produce
#      byte-identical exporter output under PROTOCOL §4.5.1's construction?
#
# Everything runs on one laptop. The Android side is stood in for by Conscrypt-over-BoringSSL on
# the JVM, which is the same TLS implementation Android ships and the same entry point that the
# public `android.net.ssl.SSLSockets.exportKeyingMaterial` (API 31+) delegates to. That substitution
# is the one thing this harness cannot prove on its own — see docs/test-results/ for how it is
# recorded, and the real-device gate for how it is closed.
#
# Nothing here is production code, nothing here is shipped, and every key it creates is a
# throwaway generated into a temporary directory that is deleted on exit.
#
# Usage:  ./run.sh            # runs every experiment, prints a result block per experiment
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONSCRYPT_VERSION="2.6.3"
CONSCRYPT_URL="https://repo1.maven.org/maven2/org/conscrypt/conscrypt-openjdk-uber/${CONSCRYPT_VERSION}/conscrypt-openjdk-uber-${CONSCRYPT_VERSION}.jar"
JAVA_HOME_21="${JAVA_HOME_21:-/opt/homebrew/opt/openjdk@21}"
OPENSSL="${OPENSSL:-/opt/homebrew/opt/openssl@3/bin/openssl}"
LABEL="EXPORTER-RideLink-SAS-v1"

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
cd "$WORK"

say() { printf '\n=== %s ===\n' "$*"; }

say "Toolchain"
"$JAVA_HOME_21/bin/java" -version 2>&1 | head -1
"$OPENSSL" version
xcrun swiftc --version 2>&1 | head -1
xcrun --sdk iphoneos --show-sdk-version | sed 's/^/iOS SDK /'

say "Fetching Conscrypt ${CONSCRYPT_VERSION} (test-only, never shipped)"
curl -fsSL -o conscrypt.jar "$CONSCRYPT_URL"
shasum -a 256 conscrypt.jar

# Throwaway P-256 identities. The real implementation generates these in Android Keystore /
# iOS Keychain and never writes a private key to disk; these exist only to give the JVM side a
# certificate to present.
say "Generating throwaway P-256 test identities"
for who in server client; do
  "$OPENSSL" ecparam -name prime256v1 -genkey -noout -out "$who.key" 2>/dev/null
  "$OPENSSL" req -new -x509 -key "$who.key" -out "$who.crt" -days 3650 \
    -subj "/CN=RideLink Device" -sha256 2>/dev/null
  "$OPENSSL" pkcs12 -export -inkey "$who.key" -in "$who.crt" -out "$who.p12" \
    -passout pass:spike -name "$who" 2>/dev/null
done
echo "server SPKI sha256 = $("$OPENSSL" x509 -in server.crt -pubkey -noout \
  | "$OPENSSL" pkey -pubin -outform DER | "$OPENSSL" dgst -sha256 -r | cut -d' ' -f1)"

say "Building harnesses"
"$JAVA_HOME_21/bin/javac" -cp conscrypt.jar -d . "$HERE/ConscryptExporterSpike.java"
xcrun swiftc -O -o applespike "$HERE/AppleExporterSpike.swift" \
  -framework Network -framework Security -framework CryptoKit 2>&1 | grep -v '^$' || true

say "Experiment 1 — Conscrypt/BoringSSL <-> Conscrypt/BoringSSL (the Android stack, both ends)"
"$JAVA_HOME_21/bin/java" -cp .:conscrypt.jar ConscryptExporterSpike pair | tee exp1.txt

say "Experiment 2 — Apple Network.framework <-> Apple Network.framework"
./applespike self | tee exp2.txt

say "Experiment 3 — Apple Network.framework client <-> Conscrypt/BoringSSL server (CROSS-STACK)"
PORT=45711
"$JAVA_HOME_21/bin/java" -cp .:conscrypt.jar ConscryptExporterSpike server "$PORT" > exp3-jvm.txt 2>&1 &
JVM_PID=$!
for _ in $(seq 1 60); do grep -q JVM_SERVER_READY exp3-jvm.txt 2>/dev/null && break; sleep 0.25; done
./applespike client "$PORT" > exp3-apple.txt 2>&1 || true
wait "$JVM_PID" 2>/dev/null || true
cat exp3-jvm.txt exp3-apple.txt

say "Experiment 4 — Apple Network.framework client <-> OpenSSL ${OPENSSL##*/} server (third stack)"
PORT=45719
rm -f keepopen.fifo && mkfifo keepopen.fifo
(sleep 25 > keepopen.fifo &)
("$OPENSSL" s_server -accept "$PORT" -naccept 1 -cert server.crt -key server.key -tls1_3 \
  -Verify 1 -keymatexport "$LABEL" -keymatexportlen 32 < keepopen.fifo > exp4-ossl.txt 2>&1 &)
sleep 2
./applespike client "$PORT" > exp4-apple.txt 2>&1 || true
sleep 3
grep -A3 -i 'keying material' exp4-ossl.txt || echo "(openssl produced no exporter block)"
grep 'APPLE_CLIENT_' exp4-apple.txt

say "VERDICT"
python3 - <<'PY'
import re, pathlib

def kv(path):
    out = {}
    for line in pathlib.Path(path).read_text().splitlines():
        if '=' in line:
            k, _, v = line.partition('=')
            out[k.strip()] = v.strip()
    return out

e1 = kv('exp1.txt')
e3j, e3a = kv('exp3-jvm.txt'), kv('exp3-apple.txt')
ossl = re.search(r'Keying material:\s*([0-9A-Fa-f]+)', pathlib.Path('exp4-ossl.txt').read_text())
e4a = kv('exp4-apple.txt')

def check(name, ok, detail=''):
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  — ' + detail) if detail else ''}")

check('E1 Conscrypt exporter reachable from public-equivalent API',
      len(e1.get('JVM_SERVER_EXPORTER_NULLCTX', '')) == 64)
check('E1 both Conscrypt endpoints agree',
      e1.get('JVM_SERVER_EXPORTER_NULLCTX') == e1.get('JVM_CLIENT_EXPORTER_NULLCTX'))
check('E1 TLS 1.3: null context == zero-length context',
      e1.get('JVM_SERVER_EXPORTER_NULLCTX') == e1.get('JVM_SERVER_EXPORTER_EMPTYCTX'))
check('E1 a non-empty context does change the output',
      e1.get('JVM_SERVER_EXPORTER_NULLCTX') != e1.get('JVM_SERVER_EXPORTER_CTX010203'))
check('E3 CROSS-STACK Apple exporter == Conscrypt exporter, same handshake',
      bool(e3a.get('APPLE_CLIENT_EXPORTER_NOCTX'))
      and e3a.get('APPLE_CLIENT_EXPORTER_NOCTX') == e3j.get('JVM_SERVER_EXPORTER_NULLCTX')
      == e3j.get('JVM_SERVER_EXPORTER_EMPTYCTX'),
      e3a.get('APPLE_CLIENT_EXPORTER_NOCTX', '<none>'))
check('E3 Apple negotiated TLS 1.3', e3a.get('APPLE_CLIENT_NEGOTIATED') == 'TLSv1.3')
check('E3 SPKI pin values cross-match in both directions',
      e3a.get('APPLE_CLIENT_OWN_SPKI_SHA256') == e3j.get('JVM_SERVER_PEER_SPKI_SHA256')
      and e3a.get('APPLE_CLIENT_PEER_SPKI_SHA256') == e3j.get('JVM_SERVER_OWN_SPKI_SHA256'))
check("E3 RideLink's own DER certificate: self-signature verifies under an independent X.509 parser",
      e3j.get('JVM_SERVER_PEER_SELFSIG_VERIFIES') == 'true')
check('E3 that certificate is within its validity window',
      e3j.get('JVM_SERVER_PEER_VALIDITY_OK') == 'true')
check('E4 third stack (OpenSSL) agrees with Apple on the same handshake',
      bool(ossl) and ossl.group(1).lower() == e4a.get('APPLE_CLIENT_EXPORTER_NOCTX', ''),
      (ossl.group(1).lower() if ossl else '<none>'))

sas = e3a.get('APPLE_CLIENT_EXPORTER_NOCTX', '')
if len(sas) >= 8:
    n = int(sas[:8], 16)
    print(f"\nPROTOCOL §4.5.1 applied to the E3 exporter output: n={n}, sas6={n % 1000000:06d}")
PY
