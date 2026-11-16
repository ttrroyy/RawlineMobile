"""Adapt the pinned qWDTT/CSQTT bridge to the private Android captcha channel."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / "creds.go"
s = p.read_text(encoding="utf-8")
start = "func requestWebViewCaptcha(streamID int, captchaErr *VkCaptchaError, mode string, timeout time.Duration, parents ...context.Context) (string, error) {"
assert s.count(start) == 1
# The original per-mode timeouts stay: auto 10 s, manual 60 s, selected 120 s.
s = s.replace(start, start + '\n\tif runtime.GOOS == "android" && desktopProbeOnly && !desktopInteractiveProbe {\n\t\treturn "", errCaptchaInteractionRequired\n\t}')
s = s.replace("if desktopBridgeMode {", 'if desktopBridgeMode && runtime.GOOS != "android" {', 1)
if '"runtime"' not in s:
    s = s.replace("import (", 'import (\n\t"runtime"', 1)
old = "waitCtx, cancel := context.WithTimeout(context.Background(), timeout)"
assert s.count(old) == 1
s = s.replace(old, "parent := bridgeContext\n\tif len(parents) > 0 && parents[0] != nil { parent = parents[0] }\n\tif parent == nil { parent = context.Background() }\n\twaitCtx, cancel := context.WithTimeout(parent, timeout)")
p.write_text(s, encoding="utf-8")

# Preserve account TURN authorization in the private bridge configuration.
p = root / "bridge_config.go"
s = p.read_text(encoding="utf-8")
marker = "type bridgeConfig struct {"
assert s.count(marker) == 1
s = s.replace(marker, marker + '\n\tVKAuth string `json:"vk_auth"`')
marker = '\t\t"captcha-mode": c.CaptchaMode, "vk-auth": "anonymous", "vk-anon-path": c.VKAnonPath,'
assert s.count(marker) == 1
s = s.replace(marker, '\t\t"captcha-mode": c.CaptchaMode, "vk-auth": c.VKAuth, "vk-anon-path": c.VKAnonPath,')
marker = '\tif c.Mode == "" {'
assert s.count(marker) == 1
s = s.replace(marker, '\tif c.VKAuth == "" { c.VKAuth = "anonymous" }\n\tif c.VKAuth != "anonymous" && c.VKAuth != "account" { return nil, fmt.Errorf("invalid VK account mode") }\n' + marker)
p.write_text(s, encoding="utf-8")
p = root / "csqtt_bridge.go"
s = p.read_text(encoding="utf-8")
a = s.index("\tgo func() {\n\t\tdefer cancel()\n\t\tscanner := bufio.NewScanner(os.Stdin)")
b = s.index("\t}()", a) + len("\t}()")
s = s[:a] + s[b:]
marker = "\tconfigs := make(chan string, 1)"
assert s.count(marker) == 1
s = s.replace(marker, '''\tgo func() {
        defer cancel()
        scanner := bufio.NewScanner(os.Stdin)
        scanner.Buffer(make([]byte, 4096), 32768)
        for scanner.Scan() {
            line := strings.TrimSpace(scanner.Text())
            if line == "STOP" { cancel(); return }
            if runtime.GOOS == "android" && strings.HasPrefix(line, "CAPTCHA_RESULT|") { send(line) }
        }
    }()
''' + marker)
marker = '\t\t\tvisible := parts[1] != "auto"'
assert s.count(marker) == 1
s = s.replace(marker, '\t\t\tif runtime.GOOS == "android" {\n\t\t\t\tif c.ProbeOnly && !c.InteractiveCaptcha { send("CAPTCHA_RESULT|error:cancelled"); continue }\n\t\t\t\tfmt.Println(line[i:]); continue\n\t\t\t}\n' + marker)
p.write_text(s, encoding="utf-8")

# The account stdin protocol uses one result channel: serialize concurrent hashes.
p = root / "vk_account.go"
s = p.read_text(encoding="utf-8")
marker = "func requestAccountTurnCreds(ctx context.Context, link string, streamID int) (string, string, []string, error) {"
assert s.count(marker) == 1
s = s.replace(marker, "var RawlineAccountAuthMu sync.Mutex\n\n" + marker + "\n\tRawlineAccountAuthMu.Lock()\n\tdefer RawlineAccountAuthMu.Unlock()")
p.write_text(s, encoding="utf-8")


# Current desktop sources already contain netstack tuning; retain authenticated UDP ownership and private CSQTT IPC.
import shutil
def replace_once(source: str, before: str, after: str) -> str:
    if source.count(before) != 1:
        raise ValueError("Pinned SOCKS implementation changed; review the mobile source-port patch")
    return source.replace(before, after, 1)


def prepare(folder):
    path = folder / "bridge_socks.go"
    source = path.read_text(encoding="utf-8")
    source = replace_once(source, "socksUDPAssociation(ctx, c, dial, listen)", "socksUDPAssociation(ctx, c, dial, listen, address)")
    source = replace_once(source,
        "func socksUDPAssociation(parent context.Context, control net.Conn, dial bridgeDial, listen bridgeListen) error {",
        """func socksUDPAssociation(parent context.Context, control net.Conn, dial bridgeDial, listen bridgeListen, requestedAddress string) error {
	// A local IP identifies every Android app; only the port reserved before TCP authentication identifies this client.
	controlIP := control.RemoteAddr().(*net.TCPAddr).IP
	requestedHost, requestedPortText, addressErr := net.SplitHostPort(requestedAddress)
	requestedPort, portErr := strconv.Atoi(requestedPortText)
	requestedIP := net.ParseIP(requestedHost)
	if addressErr != nil || portErr != nil || requestedPort < 1 || requestedPort > 65535 || requestedIP == nil ||
		(!requestedIP.IsUnspecified() && !requestedIP.Equal(controlIP)) {
		_ = socksReply(control, 2, "")
		return fmt.Errorf("authenticated UDP association requires the client's reserved source port")
	}""")
    source = replace_once(source, "\tcontrolIP := control.RemoteAddr().(*net.TCPAddr).IP\n\tbuf := make([]byte, 65535)",
        "\tbuf := make([]byte, 65535)")
    source = replace_once(source,
        "if !source.IP.Equal(controlIP) || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {",
        "if !source.IP.Equal(controlIP) || source.Port != requestedPort || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {")
    path.write_text(source, encoding="utf-8", newline="\n")
    bridge = folder / "csqtt_bridge.go"
    source = bridge.read_text(encoding="utf-8")
    source = replace_once(source,
        '\tinput, err := command.StdinPipe()',
        '''\tipc, err := newMobileCSQTTIPC(cache)
\tif err != nil { return err }
\tdefer ipc.Close()
\tcommand.Args = append(command.Args, "--tun-fd-uds", ipc.path)
\tinput, err := command.StdinPipe()''')
    source = replace_once(source, '''\taddress, err := net.ResolveUDPAddr("udp4", c.Listen)
\tif err != nil {
\t\tdevice.Close()
\t\treturn err
\t}
\tsocket, err := net.DialUDP("udp4", nil, address)
\tif err != nil {
\t\tdevice.Close()
\t\treturn err
\t}''', '''\tsocket, err := ipc.attach(ctx)
\tif err != nil {
\t\tdevice.Close()
\t\treturn err
\t}''')
    bridge.write_text(source, encoding="utf-8", newline="\n")
    relay = folder / "internal/csqtt/relay.go"
    source = relay.read_text(encoding="utf-8")
    source = replace_once(source, "socket *net.UDPConn)", "socket io.ReadWriteCloser)")
    relay.write_text(source, encoding="utf-8", newline="\n")
    for path in (Path(__file__).resolve().parents[1] / "core-patches/carrier-socks/qwdtt").glob("*.go"):
        shutil.copyfile(path, folder / path.name)

prepare(root)

# The current desktop bridge already understands auto_js, unified obfuscation and negotiated workers.
p = root / "csqtt_bridge.go"
s = p.read_text(encoding="utf-8")
s = replace_once(s, '"--tun-fd-uds", ipc.path', '"--tun-uds", ipc.path')
s = replace_once(s, '\t_ = socket.SetReadBuffer(socketBufSize)\n\t_ = socket.SetWriteBuffer(socketBufSize)', '')
s = replace_once(s, 'c.Workers = csqtt.NormalizeWorkers(c.Workers, len(c.Hashes), false)',
    'if c.Workers <= 5 { c.Workers = 5 } else { c.Workers = max(5, min(c.Workers, 100)/10*10) }')
p.write_text(s, encoding="utf-8", newline="\n")
p = root / "mobile_source_port_test.go"
s = p.read_text(encoding="utf-8")
s = replace_once(s, 'serveBridgeSOCKS(ctx, listener, dial, "mobile-user", "mobile-password")',
    'serveBridgeSOCKS(ctx, listener, dial, nil, "mobile-user", "mobile-password")')
p.write_text(s, encoding="utf-8", newline="\n")
