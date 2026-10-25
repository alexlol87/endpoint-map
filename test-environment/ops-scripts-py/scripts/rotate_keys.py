#!/usr/bin/env python3
import os
import sys


def main() -> int:
    keys = [k for k in os.environ if k.endswith("_API_KEY")]
    print(f"would rotate {len(keys)} keys")
    return 0


if __name__ == "__main__":
    sys.exit(main())
