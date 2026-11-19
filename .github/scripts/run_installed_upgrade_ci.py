#!/usr/bin/env python3
"""Run each real installed-v1 campaign phase once; retain and fail on its first error."""
import argparse
from pathlib import Path

import package_upgrade_build
import run_package_upgrade


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-record", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    value = package_upgrade_build.load(args.build_record)
    package_upgrade_build.validate(value)
    campaign = args.build_record.resolve().parent / "campaign"
    common = ["--campaign", str(campaign), "--serial", args.serial, "--disposable-device", args.serial,
              "--allow-package", run_package_upgrade.PACKAGE]
    prepare = ["prepare", *common, "--old-source", value["oldSource"], "--candidate-source", value["candidateSource"],
               "--baseline-origin", value["baselineOrigin"], "--build-record", str(args.build_record.resolve()),
               "--build-record-sha256", package_upgrade_build.sha256(args.build_record)]
    for role, option in (("old", "old"), ("candidate", "candidate"), ("tests", "test"), ("seed", "seed")):
        prepare.extend([f"--{option}-apk", value["apks"][role]["path"], f"--{option}-sha256", value["apks"][role]["sha256"]])
    for arguments in (prepare, ["seed", *common], ["freeze", *common], ["upgrade", *common]):
        result = run_package_upgrade.main(arguments)
        if result:
            return result
    result = package_upgrade_build.load(campaign / "campaign.json")
    expected = {"phase": "passed-scoped-package-campaign", "qualified": True, "installedV1MigrationQualified": True,
                "replacementScope": "increasing-version-test-update", "oldSource": value["oldSource"],
                "candidateSource": value["candidateSource"], "publisherQualified": False, "storeReleaseQualified": False}
    if (any(result.get(key) != item or type(result.get(key)) is not type(item) for key, item in expected.items())
            or "firstFailure" in result or "automatedOldAppSeed" not in result or "manualOldAppSeed" in result):
        raise ValueError("The required actual v1 and increasing-version update scope did not pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
