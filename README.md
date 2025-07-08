# nf-python: A Nextflow ↔ Python Integration Plugin

nf-python is a Nextflow plugin enabling seamless integration between Nextflow and Python scripts. Python scripts can be invoked as part of your Nextflow workflow, and arguments and outputs will be serialized back-and-forth automatically, making them available as native pythonic objects.

## Installation
`nf-python` does not handle python environment setup itself. `python` needs to be in the system path and have the pypi package `nf-python-plugin` installed:
```bash
pip install nf-python-plugin
```
🚨 Please note: installing `nf-python-plugin` through the built-in conda support is not working yet.

Then, to use the plugin in your Nextflow data pipelines, simply include it by writing `include { pyFunction } from 'plugin/nf-python'` and it will be downloaded and installed. For all setup options, please refer to the [Nextflow plugins documentation](https://www.nextflow.io/docs/latest/plugins.html).

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

## Install from source

If you want to build and run this plugin from source, you can use this method:

```bash
git clone git@github.com:royjacobson/nf-python.git && cd nf-python
pip install py/  # Install in the appropriate python environment
make buildPlugins
export VER="0.1.2"  # Change appropriately
cp -r build/plugins/nf-python-${VER} ~/.nextflow/plugins/

export NXF_OFFLINE=true
nextflow my_flow.nf -plugins "nf-python@${VER}"
```

## License

See `COPYING` for license information.
The layout of the project is based on the [nf-hello](https://github.com/nextflow-io/nf-hello.git) project, licensed under the Apache License 2.0.
