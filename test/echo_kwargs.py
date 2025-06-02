import nf_python

args = nf_python.nextflow.args
opts = nf_python.nextflow.opts

output = {
    **args,
    **opts,
}

nf_python.nextflow.output(**output)
