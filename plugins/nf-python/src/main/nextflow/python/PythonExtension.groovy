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
import nextflow.util.MemoryUnit
import nextflow.util.VersionNumber
import nextflow.util.Duration

@CompileStatic
class PythonExtension extends PluginExtensionPoint {
    private Session session
    
    @Override
    void init(Session session) {
        this.session = session
    }

    // Shared logic for running a Python script
    private static def runPythonScript(Map opts, def argsForChannel = null) {
        def script = opts.script
        if (!script) throw new IllegalArgumentException('Missing script argument')
        def forwardedOpts = opts.findAll { k, v -> k != 'script' }
        def inputMap = [
            args: packGroovy(argsForChannel != null ? argsForChannel : [:]),
            opts: packGroovy(forwardedOpts)
        ]
        def infile = File.createTempFile('nfpy_in', '.json')
        infile.deleteOnExit()
        infile.text = JsonOutput.toJson(inputMap)
        def outfile = File.createTempFile('nfpy_out', '.json')
        outfile.deleteOnExit()

        def proc = ['python', script] as String[]
        def env = [
            'NEXTFLOW_INFILE': infile.absolutePath,
            'NEXTFLOW_OUTFILE': outfile.absolutePath,
            'NEXTFLOW_PYTHON_COMPAT_VER': '1',
        ]
        def pb = new ProcessBuilder(proc)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        def process = pb.start()
        process.inputStream.eachLine { println "[python] $it" }
        int rc = process.waitFor()
        if (rc != 0) throw new RuntimeException("Python script failed: $script")

        def result = new JsonSlurper().parse(outfile)
        return unpackPython(result)
    }

    @Operator
    DataflowWriteChannel pyOperator(DataflowReadChannel source, Map opts) {
        final target = CH.createBy(source)
        final next = { args ->
            def unpacked = runPythonScript(opts, args instanceof Map ? args : [value: args])
            target.bind(unpacked)
        }
        final done = { target.bind(Channel.STOP) }
        DataflowHelper.subscribeImpl(source, [onNext: next, onComplete: done])
        return target
    }

    @Function
    def pyFunction(Map opts) {
        return runPythonScript(opts)
    }

    // Helper: packGroovy serializes Groovy/Java types to the plugin protocol
    static def packGroovy(obj) {
        if (obj == null) return ["Null", null]
        if (obj instanceof List) return ["List", obj.collect { packGroovy(it) }]
        if (obj instanceof Set) return ["Set", obj.collect { packGroovy(it) }]
        if (obj instanceof Map) return ["Map", obj.collect { k, v -> [packGroovy(k), packGroovy(v)] }.toList()]
        if (obj instanceof String) return ["String", obj]
        if (obj instanceof Integer) return ["Integer", obj]
        if (obj instanceof Long) return ["Integer", obj]
        if (obj instanceof BigDecimal) return ["Decimal", obj]
        if (obj instanceof Double || obj instanceof Float) return ["Float", obj]
        if (obj instanceof Boolean) return ["Boolean", obj]
        if (obj instanceof java.nio.file.Path) return ["Path", obj.toString()]
        if (obj instanceof Duration) return ["Duration", obj.toMillis()]
        if (obj instanceof java.time.Duration) return ["Duration", obj.toMillis()]
        if (obj instanceof MemoryUnit) return ["MemoryUnit", obj.toBytes()]
        if (obj instanceof VersionNumber) return ["VersionNumber", [obj.getMajor(), obj.getMinor(), obj.getPatch()]]
        // Add more as needed
        throw new IllegalArgumentException("Cannot serialize type: ${obj?.getClass()}")
    }
    // Helper: unpackPython deserializes plugin protocol to Groovy/Java types
    static def unpackPython(obj) {
        if (!(obj instanceof List)) return obj
        List objList = (List)obj
        if (objList.size() != 2) return obj
        def type = objList.get(0)
        def data = objList.get(1)
        switch(type) {
            case 'List': return data instanceof List ? data.collect { unpackPython(it) } : []
            case 'Set': return data instanceof List ? (data.collect { unpackPython(it) } as Set) : [] as Set
            case 'Map':
                if (data instanceof List) {
                    def result = [:]
                    for (def entry : data) {
                        if (entry instanceof List && entry.size() == 2) {
                            result[unpackPython(entry[0])] = unpackPython(entry[1])
                        }
                    }
                    return result
                } else {
                    return [:]
                }
            case 'String': return data
            case 'Integer': return data
            case 'Decimal': return data instanceof String ? new BigDecimal(data as String) : null
            case 'Float': return data
            case 'Boolean': return data
            case 'Null': return null
            case 'Path': return data instanceof String ? java.nio.file.Paths.get(data) : null
            case 'Duration': return data != null ? Duration.of(data as long) : null
            case 'MemoryUnit':
                return data != null ? new MemoryUnit(data as long) : null
            case 'VersionNumber':
                if (data instanceof List && data.size() == 3) {
                    // Compose a string like 'major.minor.patch' for the constructor
                    def verStr = "${data[0]}.${data[1]}.${data[2]}"
                    return new VersionNumber(verStr)
                } else {
                    return null
                }
            // Add more as needed
            default: throw new IllegalArgumentException("Unknown type from Python: $type")
        }
    }
}
