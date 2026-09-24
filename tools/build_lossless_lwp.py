import base64
import hashlib
import json
import os
import zipfile
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Resolve key strictly from environment / .env
def resolve_key():
    k = os.environ.get("PROVIDER_MODULE_KEY")
    if k and k.strip():
        return k.strip()
    if os.path.isfile(".env"):
        with open(".env", "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("PROVIDER_MODULE_KEY="):
                    return line.split("=", 1)[1].strip()
    raise ValueError("PROVIDER_MODULE_KEY not found in environment or .env file!")

KEY_B64 = resolve_key()
KEY = base64.b64decode(KEY_B64)
KEY_ID = hashlib.sha256(KEY).hexdigest()[:16]

print(f"Key len: {len(KEY)} bytes, KeyId: {KEY_ID}")

# Single raw source lives ONLY at repo base: provider.json (no deep addon dirs).
# provider.js is kept for reference and is never read.
if os.path.isfile("provider.json"):
    with open("provider.json", "r", encoding="utf-8") as f:
        PROVIDER_JSON_RAW = f.read()
    print(f"Loaded raw provider source from provider.json ({len(PROVIDER_JSON_RAW)} chars)")
else:
    PROVIDER_JSON_RAW = ""
# JSON-only addon: NO provider.js. Encrypted config.json (url+secret, LWP2
# AAD-bound) + plaintext manifest. All logic lives in app (LosslessMusicApi).
def resolve_opt(name: str, default: str = "") -> str:
    v = os.environ.get(name, "").strip()
    if v:
        return v
    if os.path.isfile(".env"):
        with open(".env", "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s.startswith(f"{name}="):
                    return s.split("=", 1)[1].strip()
    return default

def lwp2_encrypt(plain: bytes, entry_name: str) -> bytes:
    n = os.urandom(12)
    ct = aesgcm.encrypt(n, plain, entry_name.encode("utf-8"))
    return b"LWP2" + n + ct

def lwp2_decrypt(env: bytes, entry_name: str) -> bytes:
    assert env[:4] == b"LWP2"
    return aesgcm.decrypt(env[4:16], env[16:], entry_name.encode("utf-8"))

if len(KEY) != 32:
    raise ValueError(f"PROVIDER_MODULE_KEY must decode to 32 bytes, got {len(KEY)}")

aesgcm = AESGCM(KEY)

# URL+secret live ONLY here (private tools/, gitignored). Public repo gets
# provider.example only. Official CI injects via env/secrets.
# Base-folder single file wins over env/.env when present.
_cfg_file = {}
if os.path.isfile("provider.json"):
    try:
        with open("provider.json", "r", encoding="utf-8") as _f:
            _cfg_file = json.loads(_f.read())
    except Exception as _e:
        print(f"WARNING: provider.json unreadable ({_e}); falling back to env/.env.")
BASE_URL = (_cfg_file.get("baseUrl", "") or "").strip() or resolve_opt("URL") or resolve_opt("BASE_URL")
URL_SECRET = (_cfg_file.get("apiKey", "") or "").strip() or resolve_opt("KEY") or resolve_opt("URL_SECRET")
if not URL_SECRET:
    print("WARNING: URL_SECRET empty — addon will carry baseUrl only (native injects key at runtime if configured).")

CONFIG_OBJ = {
    "baseUrl": BASE_URL,
    "apiKey": URL_SECRET,
}
CONFIG_JSON = json.dumps(CONFIG_OBJ, indent=2).encode("utf-8")
if len(CONFIG_JSON) == 0 or len(CONFIG_JSON) > 64 * 1024:
    raise ValueError("config.json bad size")

cfg_envelope = lwp2_encrypt(CONFIG_JSON, "config.json")
assert lwp2_decrypt(cfg_envelope, "config.json") == CONFIG_JSON
print(f"Config plaintext {len(CONFIG_JSON)}B -> envelope {len(cfg_envelope)}B")
print("Decryption verification: SUCCESS (LWP2 AAD-bound config.json)!")

# Manifest definition (JSON-only)
MANIFEST = {
    "id": "rushtify.lossless.provider",
    "name": "Rushtify Lossless Engine",
    "version": "1.0.4",
    "versionCode": 6,
    "description": "Lossless backend config (URL+secret, logic in app)",
    "author": "Rushtify",
    "entryPoint": "config.json",
    "global": "RushtifyProvider",
    "targetApi": 2,
    "capabilities": ["config", "lossless", "hires", "playback"],
    "encrypted": True,
    "enc": {
        "alg": "AES-256-GCM",
        "format": "LWP2",
        "files": ["config.json"],
        "keyId": KEY_ID
    }
}

manifest_bytes = json.dumps(MANIFEST, indent=2).encode("utf-8")

# Package into lossless.lwp (JSON-only, no provider.js)
out_dir = os.path.join("app", "src", "main", "assets", "modules")
os.makedirs(out_dir, exist_ok=True)
out_file = os.path.join(out_dir, "lossless.lwp")

with zipfile.ZipFile(out_file, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("manifest.json", manifest_bytes)
    zf.writestr("config.json", cfg_envelope)

# Also write to root directory lossless.lwp
with open("lossless.lwp", "wb") as f:
    with open(out_file, "rb") as src:
        f.write(src.read())

print(f"Created {out_file} successfully! Size: {os.path.getsize(out_file)} bytes")
