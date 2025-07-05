#!/usr/bin/env nextflow

nextflow.enable.dsl = 2

include { pyFunction } from 'plugin/nf-python'
import nextflow.util.VersionNumber as VersionNumber

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
    // Simple identity roundtip tests for all supported types
    testCases = [
        [x: 10, y: 3],
        [value: 1, foo: 'bar', bar: 123],
        [foo: 'standalone', bar: 42],
        [a: 1, b: 2],
        [value: 42],
        [value: 'hello'],
        [value: true],
        [value: null],
        [value: [1, 2, 3]],
        [value: [4, 5, 6] as Set],
        [value: '/tmp/example.txt'],
        [value: Duration.of(90000)],
        [value: MemoryUnit.of(10241024)],
        [value: new VersionNumber('1.2.3')]
    ]

    testCases.each { testCase ->
        result = pyFunction(script: 'echo_kwargs.py', *:testCase)
        println "[TEST] in: $testCase roundtrip: $result"
        assert result == testCase
    }

    // java.time.Duration gets cast to nextflow.util.Duration
    assert [value: Duration.of(90000)] == pyFunction(script: 'echo_kwargs.py', value: java.time.Duration.ofSeconds(90))

    // Floating point tests
    floatTestCases = [
        [value: 3.14f],
        [value: 3.14],
        [value: -3],
        [value: 1e120],
        [value: 1e-50],
        [value: Double.POSITIVE_INFINITY],
        [value: Double.NEGATIVE_INFINITY],
        [value: Double.NaN],
        [value: Float.POSITIVE_INFINITY],
        [value: Float.NEGATIVE_INFINITY],
        [value: Float.NaN]
    ]
    floatTestCases.each { testCase ->
        result = pyFunction(script: 'echo_kwargs.py', *:testCase)
        println "[TEST] in: $testCase roundtrip: $result"
        // Approximate assert for floating point values
        assert (result.value instanceof Number)
        if (Double.isNaN(testCase.value)) {
            assert Double.isNaN(result.value)
        } else if (Double.isInfinite(testCase.value)) {
            assert Double.isInfinite(result.value) && Math.signum(result.value) == Math.signum(testCase.value)
        } else {
            assert Math.abs(result.value - testCase.value) / Math.abs(result.value + testCase.value) < 0.0001
        }
    }

    // Inline code tests
    inlineCodeTests = [
        [
            name: 'basic inline',
            code: """
            nf.output(arg1=123, arg2='abc')
            """,
            args: [:],
            expected: [arg1: 123, arg2: 'abc']
        ],
        [
            name: 'inline with args',
            code: """
            nf.output(arg1=123, arg2='abc', arg3=nf.args['foo'])
            """,
            args: [foo: 1],
            expected: [arg1: 123, arg2: 'abc', arg3: 1]
        ]
    ]

    inlineCodeTests.each { testCase ->
        result = pyFunction(testCase.code, *:testCase.args)
        assert result == testCase.expected
        println "[TEST] pyFunction with ${testCase.name} code returned: $result"
    }

    // Test it works inside a process
    PyProcess(11, 33)
        .view { result ->
            assert result == [x: 11, y: 33]
            println "[TEST] PyProcess returned: $result"
        }
}
