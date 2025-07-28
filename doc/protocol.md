# nf-python Protocol Specification

`nf-python` currently uses environment variables and a custom JSON-based serialization protocol
for data passage between Nextflow and the Python code. This is subject to changes in the future.
The protocol is described here:

## Environment Variables

- `NEXTFLOW_INFILE`: Path to a temporary JSON file containing serialized input arguments
- `NEXTFLOW_OUTFILE`: Path to a temporary JSON file where serialized output are written by the Python code

## JSON Type System

Data is exchanged using typed JSON arrays in the format `[type, value]`, where:
- `type`: String identifying the data type
- `value`: The actual data, possibly containing nested typed values

## Type Conversions

The following types are supported for usage as arguments and output values,
and are automatically serialized and deserialized between native runtime objects.

| Nextflow/Groovy Type | JSON Type | Python Type |
|-------------|-----------|------------|
| `null` | `["Null", null]` | `None` |
| `List` | `["List", [...]]` | `list` |
| `Set` | `["Set", [...]]` | `set` |
| `Map` | `["Map", [[k1, v1], [k2, v2]]]` | `dict` |
| `String` | `["String", "..."]` | `str` |
| `Integer(*)`, `Long` | `["Integer", n]` | `int` |
| `BigDecimal` | `["Decimal", "..."]` | `decimal.Decimal` |
| `Double(*)`, `Float` | `["Float", n]` or `["Float", "inf/nan/-inf"]` | `float` |
| `Boolean` | `["Boolean", true/false]` | `bool` |
| `Path` | `["Path", "..."]` | `pathlib.Path` |
| `nextflow.util.Duration(*)`, `java.time.Duration` | `["Duration", ms]` | `datetime.timedelta` |
| `nextflow.util.MemoryUnit` | `["MemoryUnit", bytes]` | `MemoryUnit` |
| `nextflow.util.VersionNumber` | `["VersionNumber", [major, minor, patch]]` | `VersionNumber` |

(*): Used as a Groovy type when deserializing result when multiple types map to the same Python type.
