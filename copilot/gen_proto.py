"""Generate gRPC stubs from the shared proto into fleet_copilot/rpc (not committed)."""
from pathlib import Path

from grpc_tools import protoc

ROOT = Path(__file__).resolve().parent
PROTO = ROOT.parent / "common" / "src" / "main" / "proto"
if not PROTO.exists():  # container build copies the proto next to this file
    PROTO = ROOT / "proto"

code = protoc.main([
    "grpc_tools.protoc",
    f"-Ifleet_copilot/rpc={PROTO}",
    f"--python_out={ROOT}",
    f"--grpc_python_out={ROOT}",
    f"{PROTO}/fleet_data.proto",
])
if code != 0:
    raise SystemExit(code)
