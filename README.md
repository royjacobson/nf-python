# nf-python: A Nextflow ↔ Python Integration Plugin

`nf-python` is a Nextflow plugin enabling seamless integration between Nextflow and Python scripts. Python scripts can be invoked as part of your Nextflow workflow, and arguments and outputs will be serialized back-and-forth automatically, making them available as native pythonic objects.

---

## Example Usage

```groovy
include { pyOperator; pFunction } from 'plugin/nf-python'

Channel.of(1, 2, 3)
    .pyOperator(script: 'my_python_script.py', foo: 'bar')
    .view()

pyFunction('my_other_script.py', bar: 'too')
```

- Channel data is passed as `nextflow.args` in Python.
- Options (like `foo`) are passed as `nextflow.opts`.

```python
# example_script.py
import nextflow

# Access arguments and options
print(nextflow.args)
print(nextflow.opts)

# Assign output
nextflow.output(result=nextflow.args[0] + 1)
```

### Build & Test

```bash
make buildPlugins
cp build/plugins/nf-python $HOME/.nextflow/plugins
nextflow run poc_python.nf -plugins nf-python
```

## Contributing

Contributions and feedback are welcome! This project is in a preliminary exploration stage and lots of possible functionality remains to be added.

## License

See `COPYING` for license information.
The layout of the project is based on the [nf-hello](https://github.com/nextflow-io/nf-hello.git) project, licensed under the Apache License 2.0.
