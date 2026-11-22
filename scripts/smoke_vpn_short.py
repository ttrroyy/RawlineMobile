"""One VPN connection and HTTP probe against the isolated local SOCKS fixture."""
from pathlib import Path

source = Path(__file__).with_name('smoke_vpn.py').read_text()
setup = source.split("shell('am','start','-n',activity,'-a','android.intent.action.VIEW','-d','socks5://127.0.0.1:8868#VPN-queued')")[0]
setup = setup.replace('assert len(packages)==1', "packages = [p for p in packages if not p.endswith('.test')]\nassert len(packages)==1")
exec(compile(setup, 'smoke_vpn.py setup', 'exec'))
if os.environ.get('TEST_SYSTEM_TUN') == 'true':
    tap('Settings')
    tap('TUN and proxy settings')
    tap('TCP/IP stack')
    tap('System')
    wait_for('Uses the system network stack.')
    shell('input', 'keyevent', '4')
    shell('input', 'keyevent', '4')
    wait_for('VPN-smoke')
tap('VPN-smoke')
tap('Connect or disconnect VPN, Not connected')
time.sleep(2)
for _ in range(30):
    screen = xml()
    if 'Connection request' in screen:
        tap('OK')
    elif 'Connect or disconnect VPN, Connected' in screen:
        break
    time.sleep(1)
wait_for('Connect or disconnect VPN, Connected')
state = shell('dumpsys', 'connectivity')
assert has_vpn_network(state)
previous = len(hits)
tap('Check current connection')
for _ in range(20):
    if len(hits) > previous and re.search(r'\d+ ms', xml()):
        break
    time.sleep(1)
else:
    raise AssertionError('Current connection HTTP probe failed')
# The subscription and profile are painted near the bottom of the connect control.
# Tap that caption rather than the center of its Android accessibility bounds.
connected_label = 'Connect or disconnect VPN, Connected'
orb = next((node for node in ET.fromstring(xml()).iter('node')
            if node.get('content-desc') == connected_label), None)
assert orb is not None, 'Connected control is missing'
left, top, right, bottom = map(int, re.findall(r'\d+', orb.attrib['bounds']))
caption_y = top + round((bottom - top) * 0.93)
shell('input', 'tap', str((left + right) // 2), str(caption_y))
wait_for('Connections')
shell('input', 'keyevent', '4')
wait_for(connected_label)
# An unsigned command from the shell must not silently stop a connected VPN.
shell('am', 'start', '-n', package + '/app.rawline.mobile.ui.QuickDisableShortcut')
time.sleep(2)
state = shell('dumpsys', 'connectivity')
assert has_vpn_network(state)
assert 'VPN control' in xml(), 'Unsigned command did not require confirmation'
tap('Cancel')
wait_for('Connect or disconnect VPN, Connected')
tap('Connect or disconnect VPN, Connected')
wait_for('Connect or disconnect VPN, Not connected')
server.shutdown()
print('VPN connect, HTTP latency, connections navigation, unsigned disable protection and disconnect passed')
