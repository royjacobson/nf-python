# nf-python: A Nextflow ↔ Python Integration Plugin

nf-python is a Nextflow plugin enabling seamless integration between Nextflow and Python scripts. Python scripts can be invoked as part of your Nextflow workflow, and arguments and outputs will be serialized back-and-forth automatically, making them available as native pythonic objects.

---

## Installation
⚠️ nf-python is not yet available in the Nextflow plugin repository, so manual compilation and the usage of NXF_OFFLINE are required.

⚠️ On the python side, you'll need to install the nf-python package (soon to be available in pypi).

If you still want to experiment with this plugin, this is the way to do it right now:
```bash
git clone git@github.com:royjacobson/nf-python.git && cd nf-python
pip install py/
make buildPlugins
cp -r build/plugins/nf-python-0.0.1 ~/.nextflow/plugins/
export NXF_OFFLINE=true

nextflow my_flow.nf -plugins 'nf-python@0.0.1'
```

## Example Usage
To use a python script as part of your Nextflow pipeline, import either `pyOperator` or `pyFunction` from the `nf-plugin`.

```groovy
include { pyOperator; pyFunction } from 'plugin/nf-python'

Channel.of(1, 2, 3)
    .pyOperator(script: 'my_python_script.py', foo: 'bar')
    .view()

pyFunction('my_other_script.py', bar: 'too')
```

Usage inside python is easy:
- Channel data is passed as `nextflow.args` in Python.
- Options (like `foo`) are passed as `nextflow.opts`.
- To return results to nextflow, use `nextflow.output(...)`
- All major native data types (lists, dicts, etc.) are supported.

```python
# example_script.py
import nextflow

# Access arguments and options
print(nextflow.args)
print(nextflow.opts)

# Assign output
nextflow.output(result=nextflow.args[0] + 1)
```

## Inline Code Support

The `pyOperator` and `pyFunction` also support inline code as a new feature. Here are some examples:

```groovy
// Inline code support (new feature):
Channel.of([1])
    .pyOperator("""
        nextflow.output(arg1=123, arg2='abc')
    """)
    .view { result ->
        println result
    }

// With options:
Channel.of([1])
    .pyOperator("""
        nextflow.output(arg1=123, arg2='abc', arg3=nextflow.opts['foo'])
    """, foo: 1)
    .view { result ->
        println result
    }

// pyFunction also supports inline code:
result = pyFunction("""
    nextflow.output(arg1=123, arg2='abc')
""")
assert result == [arg1: 123, arg2: 'abc']

result = pyFunction("""
    nextflow.output(arg1=123, arg2='abc', arg3=nextflow.opts['foo'])
""", foo: 1)
assert result == [arg1: 123, arg2: 'abc', arg3: 1]
```

## Contributing

Contributions and feedback are welcome! This project is in a preliminary exploration stage and lots of possible functionality remains to be added.

## License

See `COPYING` for license information.
The layout of the project is based on the [nf-hello](https://github.com/nextflow-io/nf-hello.git) project, licensed under the Apache License 2.0.
