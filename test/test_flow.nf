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
    // Test pyFunction in a process: should return correct sum and difference
    PyProcess(10, 3)
        .view { result ->
            assert result == [x: 10, y: 3]
            println "[TEST] PyProcess returned: $result"
        }

    // Test pyOperator with a map input
    Channel.of([x: 10, y: 3])
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result == [x: 10, y: 3]
            println "[TEST] pyOperator map input returned: $result"
        }

    // Test pyOperator with primitive input and options
    Channel.of(1)
        .pyOperator(script: 'echo_kwargs.py', foo: 'bar', bar: 123)
        .view { result ->
            assert result == [value: 1, foo: 'bar', bar: 123]
            println "[TEST] pyOperator primitive+opts returned: $result"
        }

    // Test pyFunction as a standalone function
    def standalone = pyFunction(script: 'echo_kwargs.py', foo: 'standalone', bar: 42)
    assert standalone == [foo: 'standalone', bar: 42]
    println "[TEST] pyFunction standalone returned: $standalone"

    // Test all supported types and roundtrip, one script call at a time
    Channel.of(42)
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value == 42
            println "[TEST] int roundtrip: $result"
        }

    Channel.of(3.14)
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert Math.abs(result.value - 3.14) < 1e-6
            println "[TEST] float roundtrip: $result"
        }

    Channel.of('hello')
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value == 'hello'
            println "[TEST] str roundtrip: $result"
        }

    Channel.of(true)
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value == true
            println "[TEST] bool roundtrip: $result"
        }

    Channel.of(null)
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value == null
            println "[TEST] null roundtrip: $result"
        }

    Channel.of([1, 2, 3])
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value == [1, 2, 3]
            println "[TEST] list roundtrip: $result"
        }

    Channel.of([4, 5, 6] as Set)
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert (result.value as Set) == [4, 5, 6] as Set
            println "[TEST] set roundtrip: $result"
        }

    Channel.of([a: 1, b: 2])
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result == [a: 1, b: 2]
            println "[TEST] dict roundtrip: $result"
        }

    Channel.of('/tmp/example.txt')
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value.toString() == '/tmp/example.txt'
            println "[TEST] path roundtrip: $result"
        }

    Channel.of(java.time.Duration.ofSeconds(90))
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value.toString() == 'PT1M30S' || result.value.seconds == 90
            println "[TEST] duration roundtrip: $result"
        }
    
    Channel.of(Duration.of(90000))
        .pyOperator(script: 'echo_kwargs.py')
        .view { result ->
            assert result.value.toString() == 'PT1M30S' || result.value.seconds == 90
            println "[TEST] duration roundtrip: $result"
        }

    Channel.of(10241024)
        .pyOperator(script: 'echo_kwargs.py', type: 'memory')
        .view { result ->
            // memory is returned as int, check value
            assert result.value == 10241024
            println "[TEST] memory roundtrip: $result"
        }

    Channel.of([82, 17, 49])
        .pyOperator(script: 'echo_kwargs.py', type: 'version')
        .view { result ->
            // version is returned as list, check value
            assert result.value == [82, 17, 49]
            println "[TEST] version roundtrip: $result"
        }
}
