"""Run Qoder regions in separate JVMs to test their overlapping model IDs independently.

Requires QODER_WASM_PATH and a packaged application. All HTTP upstreams are local mocks.
"""
from native_channels_integration_test import run


if __name__ == '__main__':
    run(('qoder',))
    run(('qodercn',))
