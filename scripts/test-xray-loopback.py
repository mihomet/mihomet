#!/usr/bin/env python3
"""Exercise Android's converter + bundled Mihomo against an isolated local Xray.

Requires a booted emulator with the debug app/test APKs installed, Python 3,
OpenSSL and Xray. No subscription, external proxy or root access is used.
"""

import argparse
import contextlib
import http.server
import json
from pathlib import Path
import re
import shlex
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xray", default=shutil.which("xray"), help="Xray executable")
    parser.add_argument("--adb", default=shutil.which("adb"), help="adb executable")
    parser.add_argument("--serial", required=True, help="target emulator serial")
    args = parser.parse_args()
    if not args.xray or not args.adb:
        parser.error("provide --xray and --adb (or put them in PATH)")
    adb = [args.adb, "-s", args.serial]

    def shell(command, **kwargs):
        return subprocess.run(adb + ["shell", command], check=True, **kwargs)

    observed = set()

    class Target(http.server.BaseHTTPRequestHandler):
        def do_HEAD(self):
            observed.add(self.path.removeprefix("/"))
            self.send_response(204)
            self.end_headers()

        do_GET = do_HEAD

        def log_message(self, *_):
            pass

    def free_port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    with tempfile.TemporaryDirectory(prefix="mihomet-xray-loopback-") as directory, contextlib.ExitStack() as cleanup:
        temp = Path(directory)
        cert, key = temp / "cert.pem", temp / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=fixture.invalid", "-addext", "subjectAltName=DNS:fixture.invalid",
                        "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
        target = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Target)
        fallback = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Target)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_3
        tls.set_ecdh_curve("X25519")
        tls.set_alpn_protocols(["h2", "http/1.1"])
        tls.load_cert_chain(cert, key)
        fallback.socket = tls.wrap_socket(fallback.socket, server_side=True)
        for server in (target, fallback):
            threading.Thread(target=server.serve_forever, daemon=True).start()
            cleanup.callback(server.server_close)
            cleanup.callback(server.shutdown)

        inbound, clients, ports = [], [], []
        uuid = "11111111-2222-4333-8444-555555555555"
        client_tls = {"security": "tls", "tlsSettings": {"serverName": "fixture.invalid", "allowInsecure": True}}
        server_tls = {"security": "tls", "tlsSettings": {"rejectUnknownSni": True,
                      "certificates": [{"certificateFile": str(cert), "keyFile": str(key)}]}}

        def add(name, protocol, stream, server_stream=None, flat=False):
            port = free_port()
            ports.append(port)
            users = [{"id": uuid, "encryption": "none"}] if protocol == "vless" else [{"id": uuid}]
            server_settings = {"clients": [{"id": uuid}]} if protocol in ("vless", "vmess") else {"clients": [{"password": "fixture-password"}]}
            if protocol == "vless":
                server_settings["decryption"] = "none"
            settings = {"vnext": [{"address": "10.0.2.2", "port": port, "users": users}]}
            if protocol == "trojan":
                settings = {"servers": [{"address": "10.0.2.2", "port": port, "password": "fixture-password"}]}
            if flat:
                settings = {"address": "10.0.2.2", "port": port}
                if protocol in ("vless", "vmess"):
                    settings |= {"id": uuid, "encryption": "none"} if protocol == "vless" else {"id": uuid}
                elif protocol in ("socks", "http"):
                    settings |= {"user": "fixture-user", "pass": "fixture-password"}
                    server_settings = {"accounts": [{"user": "fixture-user", "pass": "fixture-password"}]}
                    if protocol == "socks":
                        server_settings["auth"] = "password"
                else:
                    settings["password"] = "fixture-password"
                    if protocol == "shadowsocks":
                        settings["method"] = "aes-128-gcm"
                        server_settings = {"method": "aes-128-gcm", "password": "fixture-password"}
            inbound.append({"tag": name, "listen": "127.0.0.1", "port": port, "protocol": protocol,
                            "settings": server_settings, "streamSettings": server_stream or stream})
            clients.append({"name": name, "config": {"outbounds": [{"tag": name, "protocol": protocol,
                            "settings": settings, "streamSettings": stream}]}})
            return port

        add("vless-tcp", "vless", {"network": "tcp", "security": "none"})
        add("vless-ws", "vless", {"network": "ws", "wsSettings": {"path": "/fixture"}})
        add("vmess-ws", "vmess", {"network": "ws", "wsSettings": {"path": "/fixture"}})
        add("vless-grpc", "vless", {"network": "grpc", "grpcSettings": {"serviceName": "fixture"}})
        xhttp = {"network": "xhttp", "xhttpSettings": {"path": "/fixture", "mode": "stream-up"}}
        add("vless-xhttp", "vless", xhttp | client_tls, xhttp | server_tls)
        split = {"network": "xhttp", "xhttpSettings": {"path": "/fixture", "mode": "stream-up"}} | client_tls
        split_port = add("vless-xhttp-split", "vless", split, xhttp | server_tls)
        split["xhttpSettings"]["extra"] = {"downloadSettings": {"address": "10.0.2.2", "port": split_port,
            "network": "xhttp", "xhttpSettings": {"path": "/fixture"}} | client_tls}
        add("trojan-tls", "trojan", {"network": "tcp"} | client_tls, {"network": "tcp"} | server_tls)
        for protocol in ("vless", "vmess", "socks", "http", "shadowsocks"):
            add(f"{protocol}-flat", protocol, {"network": "tcp"}, flat=True)
        add("trojan-flat", "trojan", {"network": "tcp"} | client_tls, {"network": "tcp"} | server_tls, flat=True)
        add("vless-httpupgrade", "vless", {"network": "httpupgrade", "httpupgradeSettings": {"path": "/fixture", "host": "fixture.invalid"}})

        generated = subprocess.check_output([args.xray, "x25519"], text=True)
        private = re.search(r"Private\s*Key:\s*(\S+)", generated, re.IGNORECASE).group(1)
        public = re.search(r"(?:Public\s*Key|Password(?: \(PublicKey\))?):\s*(\S+)", generated, re.IGNORECASE).group(1)
        reality = {"network": "tcp", "security": "reality"}
        add("vless-reality", "vless", reality | {"realitySettings": {"serverName": "fixture.invalid",
            "fingerprint": "chrome", "publicKey": public, "shortId": "aabbccdd"}},
            reality | {"realitySettings": {"dest": f"127.0.0.1:{fallback.server_port}", "xver": 0,
            "serverNames": ["fixture.invalid"], "privateKey": private, "shortIds": ["aabbccdd"]}})
        clients.append({"name": "vless-reality-uri", "config":
            f"vless://{uuid}@10.0.2.2:{ports[-1]}?security=reality&sni=fixture.invalid&pbk={public}&sid=aabbccdd#vless-reality-uri"})

        config = temp / "server.json"
        # New Xray blocks private targets by default; allow only this fixture's HTTP endpoint.
        direct = {"protocol": "freedom", "tag": "direct", "settings": {"finalRules": [
            {"action": "allow", "network": "tcp", "ip": ["127.0.0.1/32"], "port": target.server_port}
        ]}}
        config.write_text(json.dumps({"log": {"loglevel": "info"}, "inbounds": inbound, "outbounds": [direct]}))
        fixture = temp / "clients.json"
        fixture.write_text(json.dumps({"destination": f"http://127.0.0.1:{target.server_port}", "clients": clients}))
        fixture.chmod(0o600)
        log = cleanup.enter_context((temp / "xray.log").open("w+"))
        process = subprocess.Popen([args.xray, "run", "-config", str(config)], stdout=log, stderr=subprocess.STDOUT)

        def stop_xray():
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        cleanup.callback(stop_xray)
        deadline = time.monotonic() + 10
        for port in ports:
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Local Xray server failed to start; check protocol support in this Xray build")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2):
                        break
                except OSError:
                    if time.monotonic() > deadline:
                        raise RuntimeError("Local Xray listener did not start")
                    time.sleep(.1)

        remote = "/data/local/tmp/mihomet-xray-clients.json"
        cleanup.callback(lambda: subprocess.run(adb + ["shell", f"rm -f {shlex.quote(remote)}"], capture_output=True))
        cleanup.callback(lambda: subprocess.run(adb + ["shell", "run-as org.mihomet.app rm -f cache/xray-loopback.json"], capture_output=True))
        subprocess.run(adb + ["push", str(fixture), remote], check=True, capture_output=True)
        shell(f"run-as org.mihomet.app cp {shlex.quote(remote)} cache/xray-loopback.json")
        result = shell("am instrument -w -e class org.mihomet.app.XrayLoopbackInstrumentedTest "
                       "-e xrayLoopbackFixture xray-loopback.json "
                       "org.mihomet.app.test/androidx.test.runner.AndroidJUnitRunner", capture_output=True, text=True)
        print(result.stdout)
        expected = {client["name"] for client in clients}
        if "OK (1 test)" not in result.stdout or not expected.issubset(observed):
            log.flush()
            log.seek(0)
            print("Local fixture server diagnostics:\n" + log.read()[-6000:])
            raise RuntimeError(f"Loopback verification failed; missing successful HTTP requests: {sorted(expected - observed)}")
        print(f"Verified {len(expected)} converted transports against local Xray; no public proxies contacted.")


if __name__ == "__main__":
    main()
