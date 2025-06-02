#!/usr/bin/env nextflow

nextflow.enable.dsl=2

include { pyFunction; pyOperator } from 'plugin/nf-python'

process PyProcess {
    input:
        val x
        val y
    output:
        val z
    exec:
        z = pyFunction(script: 'echo_kwargs.py', x: x, y: y)
}

workflow {
    PyProcess(10, 3)
        .view()

    Channel.of([x: 10, y: 3], [x: 1, y: 5])
        .pyOperator(script: 'echo_kwargs.py') |
        view()

    // Test all supported types and roundtrip
    Channel.of(1, 2, 3)
        .pyOperator(script: 'echo_kwargs.py', foo: 'bar', bar: 123, baz: [1,2,3], qux: [a: 1, b: 2], path: '/tmp/example.txt', duration: 90)
        .view()

    // Example: Launch a Python script as a standalone function (no channel input)
    pyFunction(script: 'echo_kwargs.py', foo: 'standalone', bar: 42)
}
