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
import java.nio.file.Path
import nextflow.conda.CondaCache
import nextflow.conda.CondaConfig

@CompileStatic
class PythonExtension extends PluginExtensionPoint {

    final private static String PYTHON_PREAMBLE = '''
from nf_python import nf

'''
    private Session session
    private String executable

    private static Path pluginDir

    static void setPluginDir(Path path) {
        pluginDir = path
    }

    private String getPythonExecutable(String condaEnv) {
        CondaCache cache = new CondaCache(session.getCondaConfig())
        java.nio.file.Path condaPath = cache.getCachePathFor(condaEnv)

        Process proc = new ProcessBuilder('conda', 'run', '-p', condaPath.toString(), 'which', 'python')
            .redirectErrorStream(true)
            .start()
        def output = proc.inputStream.text.trim()
        if (proc.waitFor() == 0 && output) {
            return output
        } else {
            throw new IllegalStateException("Failed to find Python executable in conda environment: $condaEnv\n Output: ${output}")
        }
    }

    @Override
    void init(Session session) {
        this.session = session
        this.executable = getExecutableFromConfigVals(
            session.config.navigate('nf_python.executable') ?: '',
            session.config.navigate('nf_python.conda_env') ?: ''
        )
    }

    String getExecutableFromConfigVals(_executable, _condaEnv) {
        String executable = cString(_executable)
        String condaEnv = cString(_condaEnv)
        if (executable && condaEnv) {
            throw new IllegalArgumentException("The 'executable' and 'conda_env' options cannot be used together")
        }
        if (executable) {
            return executable
        }
        if (condaEnv) {
            return getPythonExecutable(condaEnv)
        }
        return 'python'
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

    private Object runPythonScript(Map args) {
        def script = args.script
        if (!script) {
            throw new IllegalArgumentException('Missing script argument')
        }
        def excludedKeys = ['script', '_executable', '_conda_env']
        Map forwardedArgs = args.findAll { k, v -> !(k in excludedKeys) }

        executable = this.executable
        if (args.containsKey('_executable') || args.containsKey('_conda_env')) {
            executable = getExecutableFromConfigVals(args._executable, args._conda_env)
        }

        File infile = File.createTempFile('nfpy_in', '.json')
        infile.deleteOnExit()
        infile.text = JsonOutput.toJson(packGroovy(forwardedArgs))
        File outfile = File.createTempFile('nfpy_out', '.json')
        outfile.deleteOnExit()

        String[] proc = [executable, script] as String[]
        Map env = [
            'NEXTFLOW_INFILE': infile.absolutePath,
            'NEXTFLOW_OUTFILE': outfile.absolutePath,
            'PYTHONPATH': System.getenv('PYTHONPATH') ?
                System.getenv('PYTHONPATH') + File.pathSeparator + pluginDir.toString() :
                pluginDir.toString()
        ]
        ProcessBuilder pb = new ProcessBuilder(proc)

        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        Process process = pb.start()
        process.inputStream.eachLine { line -> println "[python] $line" }
        int rc = process.waitFor()
        if (rc != 0) {
            throw new nextflow.exception.ProcessEvalException("Python script evaluation failed", proc.join(' '), '', rc)
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

    private static Integer cInteger(Object obj) {
        if (obj instanceof Integer) {
            return obj
        }
        throw new IllegalArgumentException("Expected an integer, got: $obj")
    }

    private static Boolean cBoolean(Object obj) {
        if (obj instanceof Boolean) {
            return obj as Boolean
        }
        throw new IllegalArgumentException("Expected an boolean, got: $obj")
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
            case 'String': return cString(data)
            case 'Integer': return cInteger(data)
            case 'Decimal': return new BigDecimal(cString(data))
            case 'Float': return unpackFloat(data)
            case 'Boolean': return cBoolean(data)
            case 'Null': return null
            case 'Path': return java.nio.file.Paths.get(cString(data))
            case 'Duration': return Duration.of(cInteger(data))
            case 'MemoryUnit': return new MemoryUnit(cInteger(data))
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
