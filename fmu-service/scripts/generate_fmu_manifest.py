from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from xml.etree import ElementTree


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fmu", required=True, type=Path)
    parser.add_argument("--parameter-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--openmodelica-digest", required=True)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()

    with zipfile.ZipFile(args.fmu) as archive:
        root = ElementTree.fromstring(archive.read("modelDescription.xml"))
    co_simulation = root.find("CoSimulation")
    if co_simulation is None:
        raise ValueError("FMU does not declare CoSimulation")

    manifest = {
        "modelVersion": "TrainTractionBrake/1.0.0",
        "modelName": root.attrib["modelName"],
        "modelIdentifier": co_simulation.attrib["modelIdentifier"],
        "fmiVersion": root.attrib["fmiVersion"],
        "fmiType": "CoSimulation",
        "fmuSha256": sha256(args.fmu),
        "parameterSetId": sha256(args.parameter_file),
        "parameterSchemaVersion": "1",
        "openModelicaImageDigest": args.openmodelica_digest,
        "generationTool": root.attrib.get("generationTool", "unknown"),
        "targetPlatform": "linux/amd64",
        "sourceCommit": args.source_commit,
        "builtAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
