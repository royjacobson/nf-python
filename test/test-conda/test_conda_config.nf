#!/usr/bin/env nextflow

nextflow.enable.dsl = 2

include { pyFunction } from 'plugin/nf-python'

workflow {
    assert pyFunction('''
    import six
    print("Package 'six' available!")
    nf.output(True)
''') == [true]
}
