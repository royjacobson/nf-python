"""
Python integration module for Nextflow plugin (nf-python).

This module exposes input arguments and output assignment for Python scripts
executed via the Nextflow plugin. Arguments and outputs are passed via JSON files,
with file paths provided by environment variables.

Environment variables:
- NEXTFLOW_INFILE: path to JSON file containing input arguments
- NEXTFLOW_OUTFILE: path to JSON file where output should be written
"""

import os
import json
import sys

NEXTFLOW_PYTHON_COMPAT_VER = 1


class Nextflow:
    def __init__(self):
        if os.environ.get("NEXTFLOW_PYTHON_COMPAT_VER") != NEXTFLOW_PYTHON_COMPAT_VER:
            raise RuntimeError(
                "Incompatible NEXTFLOW_PYTHON_COMPAT_VER. Expected '1', got "
                f"{os.environ.get('NEXTFLOW_PYTHON_COMPAT_VER')}"
            )

        self._infile = os.environ.get("NEXTFLOW_INFILE")
        self._outfile = os.environ.get("NEXTFLOW_OUTFILE")
        if not self._infile or not self._outfile:
            raise RuntimeError(
                "NEXTFLOW_INFILE and NEXTFLOW_OUTFILE env vars must be set."
            )
        self._args, self._opts = self._load_args_and_opts()
        self._written_output = False

    def _load_args_and_opts(self):
        try:
            with open(self._infile, "r") as f:
                data = json.load(f)
                args = data.get("args", {})
                opts = data.get("opts", {})
                return args, opts
        except Exception as e:
            print(f"[nextflow.py] Failed to load input arguments: {e}", file=sys.stderr)
            return None, None

    def __del__(self):
        if not self._written_output:
            raise RuntimeWarning("Output not written before script exit.")

    @property
    def args(self):
        return self._args

    @property
    def opts(self):
        return self._opts

    def output(self, **kwargs):
        with open(self._outfile, "w") as f:
            json.dump(kwargs, f, default=str)
        self._written_output = True


nextflow = Nextflow()

__all__ = ["nextflow"]
