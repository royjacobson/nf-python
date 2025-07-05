# nf-python: A Nextflow ↔ Python Integration Plugin

nf-python is a Nextflow plugin enabling seamless integration between Nextflow and Python scripts. Python scripts can be invoked as part of your Nextflow workflow, and arguments and outputs will be serialized back-and-forth automatically, making them available as native pythonic objects.

---

## Installation
⚠️ nf-python is not yet available in the Nextflow plugin repository, so manual compilation and the usage of NXF_OFFLINE are required.

⚠️ On the python side, you'll need to install the nf-python package (available through pypi).

If you still want to experiment with this plugin, this is the way to do it right now:
```bash
git clone git@github.com:royjacobson/nf-python.git && cd nf-python
pip install py/
make buildPlugins
cp -r build/plugins/nf-python-0.1.2 ~/.nextflow/plugins/
export NXF_OFFLINE=true

nextflow my_flow.nf -plugins 'nf-python@0.1.2'
```

## Example Usage
To use a python script as part of your Nextflow pipeline, import `pyFunction` from the `nf-python` plugin:

```groovy
include { pyFunction } from 'plugin/nf-python'

pyFunction('my_other_script.py', foo: 'too')
```

Usage inside python is easy:
- Arguments (like `foo`) are passed as `nf_python.nf.args`.
- To return results to nextflow, use `nf_python.nf.output(...)`
- All major native data types (lists, dicts, etc.) are supported.

```python
# example_script.py
from nf_python import nf

# Access arguments and options
print(nf.args)
print(nf.opts)

# Assign output
nf.output(result=nf.args[0] + 1)
```

## Inline Code Support

`pyFunction` also support inline code. Here is an example:

```groovy
result = pyFunction("""
    # Code is automatically de-indented correctly
    # `nf_python.nf` is accessible as `nf`
    nf.output(arg1=123, arg2='abc')
""")
assert result == [arg1: 123, arg2: 'abc']

result = pyFunction("""
    nf.output(arg1=123, arg2='abc', arg3=nf.args['foo'])
""", foo: 1)
assert result == [arg1: 123, arg2: 'abc', arg3: 1]
```

## Contributing

Contributions and feedback are welcome! This project is in a preliminary exploration stage and lots of possible functionality remains to be added.

## License

See `COPYING` for license information.
The layout of the project is based on the [nf-hello](https://github.com/nextflow-io/nf-hello.git) project, licensed under the Apache License 2.0.
