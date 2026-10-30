"""배포 이미지의 리비전·라이선스와 최소 실행 조건을 확인한다."""

import json
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory

revision = os.environ["WATCH_IMAGE_REVISION"]
if len(revision) != 40 or any(character not in "0123456789abcdef" for character in revision):
    raise SystemExit("이미지 리비전은 전체 Git SHA여야 합니다")
repository = Path(__file__).resolve().parents[1]

images = {
    f"baton-watch-database-operations:{revision}": "BATON WATCH 데이터베이스 운영 작업",
    f"baton-watch-migrations:{revision}": "BATON WATCH 마이그레이션",
    f"baton-watch:{revision}": "BATON WATCH",
    f"baton-watch-postgres:{revision}": "BATON WATCH PostgreSQL",
}
common_labels = {
    "org.opencontainers.image.source": "https://github.com/ljkhyeong/baton-watch",
    "org.opencontainers.image.version": "0.1.0-SNAPSHOT",
    "org.opencontainers.image.revision": revision,
    "org.opencontainers.image.licenses": "Apache-2.0",
}

for image, title in images.items():
    inspected = json.loads(
        subprocess.check_output(["docker", "image", "inspect", image], text=True)
    )[0]
    labels = inspected.get("Config", {}).get("Labels") or {}
    expected = common_labels | {"org.opencontainers.image.title": title}
    invalid = {
        key: {"expected": value, "actual": labels.get(key)}
        for key, value in expected.items()
        if labels.get(key) != value
    }
    if invalid:
        raise SystemExit(f"{image}의 OCI 레이블이 올바르지 않습니다: {invalid}")

    if image.startswith("baton-watch:"):
        runtime_user = inspected.get("Config", {}).get("User")
        if runtime_user != "10001:10001":
            raise SystemExit(
                f"WATCH 이미지 기본 사용자가 올바르지 않습니다: {runtime_user}"
            )

    container = subprocess.check_output(["docker", "create", image], text=True).strip()
    try:
        with TemporaryDirectory() as directory:
            license_file = Path(directory) / "LICENSE"
            subprocess.run([
                "docker", "cp", f"{container}:/usr/share/licenses/baton-watch/LICENSE", str(license_file)
            ], check=True)
            if license_file.read_bytes() != (repository / "LICENSE").read_bytes():
                raise SystemExit(f"{image}의 LICENSE가 저장소 LICENSE와 다릅니다")
    finally:
        subprocess.run(["docker", "rm", "--volumes", container], check=True, stdout=subprocess.DEVNULL)

# 공식 cloudflared는 자체 레이블이 없으므로 Compose가 고정한 이미지의 사용자와 실행만 확인한다.
tunnel_compose = (repository / "compose.staging-tunnel.yml").read_text(encoding="utf-8")
cloudflared_image = re.search(r"^    image: (cloudflare/cloudflared:\S+)$", tunnel_compose, re.MULTILINE).group(1)
subprocess.run(["docker", "pull", "--quiet", cloudflared_image], check=True, stdout=subprocess.DEVNULL)
cloudflared_user = subprocess.check_output(
    ["docker", "image", "inspect", "--format", "{{.Config.User}}", cloudflared_image], text=True
).strip()
if cloudflared_user != "65532:65532":
    raise SystemExit("cloudflared 이미지 기본 사용자가 올바르지 않습니다")
for arguments in (["version"], ["tunnel", "run", "--help"]):
    subprocess.run([
        "docker", "run", "--rm", "--read-only", "--network", "none",
        "--entrypoint", "cloudflared", cloudflared_image, "--no-autoupdate", *arguments,
    ], check=True, stdout=subprocess.DEVNULL)
print("이미지 4개의 OCI 리비전·라이선스와 고정한 cloudflared 실행 확인 통과")
