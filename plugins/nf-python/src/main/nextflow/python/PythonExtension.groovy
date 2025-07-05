package nextflow.python

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.Session
import nextflow.util.MemoryUnit
import nextflow.util.VersionNumber
import nextflow.util.Duration

@CompileStatic
class PythonExtension extends PluginExtensionPoint {

    final private static String PYTHON_PREAMBLE = '''
from nf_python import nf

'''
    private Session session

    @Override
    void init(Session session) {
        this.session = session
    }

    @Function
    Object pyFunction(Map args, String code = '') {
        assert !(code && args.containsKey('script')) : 'Cannot use both code and script options together'
        if (code) {
            args = prepareScript(code, args)
        }

        return runPythonScript(args)
    }

    @Function
    Object pyFunction(String code = '') {
        return pyFunction([:], code)
    }

    private static String normalizeIndentation(String code) {
        String[] lines = code.split('\n')
        String firstNonEmpty = lines.find { line -> line.trim() }
        // Get a string of spaces/tabs that represents the base indentation
        String baseIndentation = firstNonEmpty ? firstNonEmpty.takeWhile { chr -> chr == ' ' || chr == '\t' } : ''
        // Remove the base indentation from all lines
        String[] normalizedLines = lines.collect { line ->
            if (line.startsWith(baseIndentation)) {
                return line.substring(baseIndentation.length())
            } else {
                return line // No change if it doesn't start with base indentation
            }
        }
        return normalizedLines.join('\n')
    }

    private static Map prepareScript(String code, Map args) {
        if (!code) {
            throw new IllegalArgumentException('Missing code argument')
        }
        if (!args) {
            args = [:]
        }
        if (args.containsKey('script')) {
            throw new IllegalArgumentException('The "script" option is reserved for the script file path and cannot be used with inline code')
        }
        // Normalize indentation to avoid issues with Python indentation
        String script = PYTHON_PREAMBLE + normalizeIndentation(code)
        File scriptFile = File.createTempFile('nfpy_code', '.py')
        scriptFile.deleteOnExit()
        scriptFile.text = script

        // Prepare options and arguments
        Map argsWithScript = args + [script: scriptFile.absolutePath]
        return argsWithScript
    }

    private static Object runPythonScript(Map args) {
        def script = args.script
        if (!script) {
            throw new IllegalArgumentException('Missing script argument')
        }
        Map forwardedArgs = args.findAll { k, v -> k != 'script' }
        File infile = File.createTempFile('nfpy_in', '.json')
        infile.deleteOnExit()
        infile.text = JsonOutput.toJson(packGroovy(forwardedArgs))
        File outfile = File.createTempFile('nfpy_out', '.json')
        outfile.deleteOnExit()

        String[] proc = ['python', script] as String[]
        Map env = [
            'NEXTFLOW_INFILE': infile.absolutePath,
            'NEXTFLOW_OUTFILE': outfile.absolutePath,
            'NEXTFLOW_PYTHON_COMPAT_VER': '1',
        ]
        ProcessBuilder pb = new ProcessBuilder(proc)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        Process process = pb.start()
        process.inputStream.eachLine { line -> println "[python] $line" }
        int rc = process.waitFor()
        if (rc != 0) {
            throw new nextflow.exception.ProcessException("Python script failed with exit code $rc: $script")
        }

        Object result = new JsonSlurper().parse(outfile)
        return unpackPython(result)
    }

    private static def packFloat(Double value) {
        if (value.isInfinite()) {
            return value > 0 ? 'inf' : '-inf'
        }
        if (value.isNaN()) {
            return 'nan'
        }
        return value
    }

    private static List packGroovy(obj) {
        if (obj == null) return ['Null', null]
        if (obj instanceof List) return ['List', obj.collect { packGroovy(it) }]
        if (obj instanceof Set) return ['Set', obj.collect { packGroovy(it) }]
        if (obj instanceof Map) return ['Map', obj.collect { k, v -> [packGroovy(k), packGroovy(v)] }.toList()]
        if (obj instanceof String) return ['String', obj]
        if (obj instanceof Integer) return ['Integer', obj]
        if (obj instanceof Long) return ['Integer', obj]
        if (obj instanceof BigDecimal) return ['Decimal', obj]
        if (obj instanceof Double || obj instanceof Float) return ['Float', packFloat(obj.doubleValue())]
        if (obj instanceof Boolean) return ['Boolean', obj]
        if (obj instanceof java.nio.file.Path) return ['Path', obj.toString()]
        if (obj instanceof Duration) return ['Duration', obj.toMillis()]
        if (obj instanceof java.time.Duration) return ['Duration', obj.toMillis()]
        if (obj instanceof MemoryUnit) return ['MemoryUnit', obj.toBytes()]
        if (obj instanceof VersionNumber) return ['VersionNumber', [obj.getMajor(), obj.getMinor(), obj.getPatch()]]
        throw new IllegalArgumentException("Cannot serialize type: ${obj?.getClass()}")
    }

    private static def unpackFloat(obj) {
        if (obj instanceof String) {
            switch (obj) {
                case 'inf': return Double.POSITIVE_INFINITY
                case '-inf': return Double.NEGATIVE_INFINITY
                case 'nan': return Double.NaN
                default: throw new IllegalArgumentException("Expected a number for special string for Float, got: $obj")
            }
        } else if (obj instanceof Number) {
            return obj
        } else {
            throw new IllegalArgumentException('Expected a string or number for unpacking float')
        }
    }

    private static String cString(Object obj) {
        if (obj instanceof String) {
            return obj
        } 
        throw new IllegalArgumentException("Expected a string, got: $obj")
    }

    private static List cList(Object obj) {
        if (obj instanceof List) {
            return obj
        }
        throw new IllegalArgumentException("Expected a list, got: $obj")
    }

    private static def unpackPython(obj) {
        List objList = cList(obj)
        if (objList.size() != 2) {
            throw new IllegalArgumentException("Expected a list of size 2 for unpacking, got: $obj")
        }
        def type = objList.get(0)
        def data = objList.get(1)
        switch(type) {
            case 'List': return cList(data).collect { unpackPython(it) }
            case 'Set': return cList(data).collect { unpackPython(it) } as Set
            case 'Map':
                def result = [:]
                for (def entry : cList(data)) {
                    if (entry instanceof List && entry.size() == 2) {
                        result[unpackPython(entry[0])] = unpackPython(entry[1])
                    } else {
                        throw new IllegalArgumentException("Expected a list of key-value pairs for Map, got: $entry")
                    }
                }
                return result
            case 'String': return data
            case 'Integer': return data
            case 'Decimal': return new BigDecimal(cString(data) as String)
            case 'Float': return unpackFloat(data)
            case 'Boolean': return data
            case 'Null': return null
            case 'Path': return java.nio.file.Paths.get(cString(data))
            case 'Duration': return Duration.of(data as long)
            case 'MemoryUnit': return new MemoryUnit(data as long)
            case 'VersionNumber':
                List dataList = cList(data)
                if (dataList.size() == 3) {
                    String verStr = "${dataList[0]}.${dataList[1]}.${dataList[2]}"
                    return new VersionNumber(verStr)
                }
                throw new IllegalArgumentException("Expected a list of 3 elements for VersionNumber, got: $data")
            default: throw new IllegalArgumentException("Unknown type from Python: $type")
        }
    }
}
