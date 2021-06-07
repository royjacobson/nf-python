package nextflow.python

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.extension.CH
import nextflow.extension.DataflowHelper
import nextflow.plugin.extension.Factory
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.Operator
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.Session

@CompileStatic
class PythonExtension extends PluginExtensionPoint {
    private Session session
    
    @Override
    void init(Session session) {
        this.session = session
    }

    @Operator
    DataflowWriteChannel pythonScript(DataflowReadChannel source, Map opts) {
        def script = opts.script
        if (!script) throw new IllegalArgumentException('Missing script argument')
        def forwardedOpts = opts.findAll { k, v -> k != 'script' }
        final target = CH.createBy(source)
        final next = { args ->
            def inputMap = [
                args: args,
                opts: forwardedOpts
            ]
            def infile = File.createTempFile('nfpy_in', '.json')
            infile.deleteOnExit()
            infile.text = JsonOutput.toJson(inputMap)
            def outfile = File.createTempFile('nfpy_out', '.json')
            outfile.deleteOnExit()

            def proc = [
                'python', script
            ] as String[]
            def env = [
                'NEXTFLOW_INFILE': infile.absolutePath,
                'NEXTFLOW_OUTFILE': outfile.absolutePath
                'NEXTFLOW_PYTHON_COMPAT_VER': '1'
            ]
            def pb = new ProcessBuilder(proc)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            def process = pb.start()
            process.inputStream.eachLine { println "[python] $it" }
            int rc = process.waitFor()
            if (rc != 0) throw new RuntimeException("Python script failed: $script")

            def result = new JsonSlurper().parse(outfile)
            target.bind(result)
        }
        final done = { target.bind(Channel.STOP) }
        DataflowHelper.subscribeImpl(source, [onNext: next, onComplete: done])
        return target
    }
}
