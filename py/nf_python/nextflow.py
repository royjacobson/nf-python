# Python integration module for Nextflow plugin (nf-python).

import os
import json
import sys
import pathlib
import datetime
import decimal

class MemoryUnit:
    UNITS = ["B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB"]
    def __init__(self, size):
        if isinstance(size, int):
            self.size = size
        elif isinstance(size, str):
            raise NotImplementedError()
    @property
    def bytes(self):
        return self.size
    @property
    def kilo(self):
        return self.size >> 10
    @property
    def mega(self):
        return self.size >> 20
    @property
    def giga(self):
        return self.size >> 30

class VersionNumber:
    def __init__(self, major, minor, patch):
        self.major = major
        self.minor = minor
        self.patch = patch
    def matches(self):
        raise NotImplementedError()

NEXTFLOW_PYTHON_COMPAT_VER = "1"

def parse_nf(serialized_object):
    nf_type, data = serialized_object
    if nf_type in ["Bag", "List", "Set"]:
        container_data = list(map(parse_nf, data))
        if nf_type in ["Bag", "List"]:
            return container_data
        else:
            return set(container_data)
    elif nf_type == "Map":
        return {parse_nf(k): parse_nf(v) for k, v in data}
    elif nf_type == "Duration":
        return datetime.timedelta(milliseconds=data)
    elif nf_type == "MemoryUnit":
        return MemoryUnit(data)
    elif nf_type == "Path":
        return pathlib.Path(data)
    elif nf_type == "VersionNumber":
        return VersionNumber(*data)
    elif nf_type == "String":
        return data
    elif nf_type == "Integer":
        return int(data)
    elif nf_type == "Decimal":
        return decimal.Decimal(data)
    elif nf_type == "Float":
        return data
    elif nf_type == "Boolean":
        return data
    elif nf_type == 'Null':
        return None
    else:
        raise ValueError(f"Unknown type {nf_type}")

def pack_python(python_object):
    if isinstance(python_object, (list, tuple)):
        return ["List", [pack_python(x) for x in python_object]]
    elif isinstance(python_object, set):
        return ["Set", [pack_python(x) for x in python_object]]
    elif isinstance(python_object, dict):
        return [
            "Map",
            [[pack_python(k), pack_python(v)] for k, v in python_object.items()],
        ]
    elif isinstance(python_object, str):
        return ["String", python_object]
    elif isinstance(python_object, int):
        return ["Integer", python_object]
    elif isinstance(python_object, decimal.Decimal):
        return ["Decimal", str(python_object)]
    elif isinstance(python_object, float):
        return ["Float", python_object]
    elif isinstance(python_object, bool):
        return ["Boolean", python_object]
    elif python_object is None:
        return ["Null", None]
    elif isinstance(python_object, pathlib.Path):
        return ["Path", str(python_object)]
    elif isinstance(python_object, datetime.timedelta):
        return ["Duration", int(python_object.total_seconds() * 1000)]
    elif isinstance(python_object, MemoryUnit):
        return ["MemoryUnit", python_object.bytes]
    elif isinstance(python_object, VersionNumber):
        return [
            "VersionNumber",
            [python_object.major, python_object.minor, python_object.patch],
        ]
    else:
        raise TypeError(f"Cannot serialize object of type {type(python_object)}")

class Nextflow:
    def __init__(self):
        self._written_output = False
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
    def _load_args_and_opts(self):
        try:
            with open(self._infile, "r") as f:
                data = json.load(f)
                args_raw = data.get("args", ["Null", None])
                opts_raw = data.get("opts", ["Null", None])
                args = parse_nf(args_raw)
                opts = parse_nf(opts_raw)
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
    def output(self, *args, **kwargs):
        if args and kwargs:
            raise ValueError("Cant pass both unnamed outputs and named outputs!")
        if args:
            with open(self._outfile, "w") as f:
                json.dump(pack_python(args), f, default=str)
        elif kwargs:
            with open(self._outfile, "w") as f:
                json.dump(pack_python(kwargs), f, default=str)
        self._written_output = True

nextflow = Nextflow()

__all__ = ["nextflow"]
