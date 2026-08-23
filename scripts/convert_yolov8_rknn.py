#!/usr/bin/env python3
"""Convert Rockchip model-zoo's optimized YOLOv8 ONNX model to RK3566 RKNN.

The model weights, calibration images and RKNN Toolkit are intentionally not
distributed by this repository. Run this script in a supported Linux x86_64
RKNN Toolkit 2 environment after obtaining those inputs from legal sources.
"""

import argparse
from pathlib import Path

from rknn.api import RKNN


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", required=True, type=Path)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--platform", default="rk3566")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    for path in (args.onnx, args.dataset):
        if not path.is_file():
            raise SystemExit(f"missing input: {path}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    rknn = RKNN(verbose=False)
    try:
        ret = rknn.config(
            mean_values=[[0, 0, 0]],
            std_values=[[255, 255, 255]],
            target_platform=args.platform,
        )
        if ret != 0:
            raise SystemExit(f"rknn.config failed: {ret}")
        ret = rknn.load_onnx(model=str(args.onnx))
        if ret != 0:
            raise SystemExit(f"rknn.load_onnx failed: {ret}")
        ret = rknn.build(do_quantization=True, dataset=str(args.dataset))
        if ret != 0:
            raise SystemExit(f"rknn.build failed: {ret}")
        ret = rknn.export_rknn(str(args.output))
        if ret != 0:
            raise SystemExit(f"rknn.export_rknn failed: {ret}")
    finally:
        rknn.release()

    print(f"RKNN model written to {args.output}")


if __name__ == "__main__":
    main()
