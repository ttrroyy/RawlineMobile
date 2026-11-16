"""Check actual Android dumpsys transport variants without starting an emulator."""
import ast
from pathlib import Path
import re
import unittest

source = ast.parse(Path(__file__).with_name("smoke_vpn.py").read_text())
function = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == "has_vpn_network")
namespace = {"re": re}
exec(compile(ast.Module(body=[function], type_ignores=[]), "smoke_vpn.py", "exec"), namespace)

class VpnNetworkTest(unittest.TestCase):
    def test_android_transport_variants(self):
        for state in ("[ Transports: WIFI|VPN Capabilities: INTERNET ]", "[ Transports: VPN Capabilities: INTERNET ]", "TRANSPORT_VPN"):
            self.assertTrue(namespace["has_vpn_network"](state), state)
        for state in ("[ Transports: WIFI Capabilities: INTERNET ]", "VPN package is installed", "[ Transports: NOT_VPN ]"):
            self.assertFalse(namespace["has_vpn_network"](state), state)

if __name__ == "__main__":
    unittest.main()
