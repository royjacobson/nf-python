#!/usr/bin/env nextflow

nextflow.enable.dsl = 2

include { pyFunction } from 'plugin/nf-python'

workflow {
    try {
        pyFunction('test')
        assert false, "Should always throw"
    } catch (java.lang.reflect.InvocationTargetException e) {
        def cause = e.getCause()
        assert cause.message.startsWith('Cannot run program "doesntexist"')
    }
}
