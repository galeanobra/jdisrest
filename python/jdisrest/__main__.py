"""Entry point of ``python -m jdisrest``, the command-line worker (``python -m jdisrest --help`` lists its options)."""
from __future__ import annotations

import sys

from ._cli import main

if __name__ == "__main__":
    sys.exit(main(prog="python -m jdisrest"))
