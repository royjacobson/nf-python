package nextflow.python

import com.google.common.hash.Hasher
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import java.nio.file.Path
import java.nio.file.Paths
import nextflow.conda.CondaCache
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.Session
import nextflow.util.CacheHelper
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import nextflow.util.VersionNumber
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class PythonExecSession {

    final Path infile
    final Path outfile
    final Path stdout
    final Path stderr
    final Path script

    PythonExecSession(Path path, String scriptPath = null) {
        infile = path.resolve('in.json')
        outfile = path.resolve('out.json')
        stdout = path.resolve('stdout.log')
        stderr = path.resolve('stderr.log')
        if (scriptPath) {
            script = Paths.get(scriptPath)
        } else {
            script = path.resolve('script.py')
        }
    }

}

@CompileStatic
class PythonExtension extends PluginExtensionPoint {

    final private static String PYTHON_PREAMBLE = '''
from nf_python import nf

'''
    static final Logger log = LoggerFactory.getLogger(PythonExtension)
    private static Path pluginDir

    private Session session
    private String executable

    static void setPluginDir(Path path) {
        pluginDir = path
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
    Object pyFunction(String code = '') {
        return pyFunction([:], code)
    }

    @Function
    Object pyFunction(Map args, String code = '') {
        assert !(code && args.containsKey('script')) : 'Cannot use both code and script options together'

        List<String> excludedKeys = ['script', '_executable', '_conda_env']
        Map forwardedArgs = args.findAll { k, v -> !(k in excludedKeys) }

        String executable = this.executable
        if (args.containsKey('_executable') || args.containsKey('_conda_env')) {
            executable = getExecutableFromConfigVals(args._executable ?: '', args._conda_env ?: '')
        }

        // Pack args once and JSON-serialize once
        List packedArgs = packGroovy(forwardedArgs)
        String serializedArgs = JsonOutput.toJson(packedArgs)
        log.trace('Serialized args: {}', serializedArgs)

        String hash = directoryHash(
            executable,
            args.containsKey('script') ? new File(args.script as String).text : code,
            serializedArgs
        )
        File executionDir = workDirForHash(hash)
        if (executionDir.exists()) {
            log.debug "Found old job directory ${executionDir}, removing."
            executionDir.deleteDir()
        }
        executionDir.mkdirs()
        PythonExecSession execDir = new PythonExecSession(
            executionDir.toPath(),
            args.containsKey('script') ? args.script as String : ''
        )
        log.debug "Starting python execution (hash: ${hash}) in: ${executionDir.absolutePath}"

        if (!args.containsKey('script')) {
            prepareScript(code, execDir.script)
        }

        execDir.infile.text = serializedArgs

        String[] proc = [executable, execDir.script] as String[]
        Map env = [
            'NEXTFLOW_INFILE': execDir.infile.toAbsolutePath().toString(),
            'NEXTFLOW_OUTFILE': execDir.outfile.toAbsolutePath().toString(),
            'PYTHONPATH': System.getenv('PYTHONPATH') ?
                System.getenv('PYTHONPATH') + File.pathSeparator + pluginDir.toString() :
                pluginDir.toString()
        ]

        ProcessBuilder pb = new ProcessBuilder(proc)
        pb.redirectOutput(execDir.stdout.toFile())
        pb.redirectError(execDir.stderr.toFile())
        pb.environment().putAll(env)

        Process process = pb.start()
        int rc = process.waitFor()
        if (rc != 0) {
            reportError(process, proc, execDir, "Python script evaluation failed")
        }

        if (execDir.outfile.exists()) {
            log.trace('Python output content: {}', execDir.outfile.toFile().text)
        } else {
            reportError(process, proc, execDir, "Python script did not produce expected output file.")
        }
        Object result = new JsonSlurper().parse(execDir.outfile.toFile())
        return unpackPython(result)
    }

    private static String directoryHash(String executable, String code, String serializedArgs) {
        Hasher hasher = CacheHelper.hasher(executable)
        hasher.putString(executable, java.nio.charset.StandardCharsets.UTF_8)
        hasher.putString(code, java.nio.charset.StandardCharsets.UTF_8)
        hasher.putString(serializedArgs, java.nio.charset.StandardCharsets.UTF_8)
        return hasher.hash()
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

    private static void prepareScript(String code, Path scriptPath) {
        if (!code) {
            throw new IllegalArgumentException('Missing code argument')
        }
        // Normalize indentation to avoid issues with Python indentation
        String script = PYTHON_PREAMBLE + normalizeIndentation(code)
        File scriptFile = scriptPath.toFile()
        log.debug "Writing python code to a temporary file: ${scriptFile.absolutePath}"
        scriptFile.text = script
    }

    private static void reportError(Process process, String[] command, PythonExecSession execDir, String errMsg) {
        String stderrContent = execDir.stderr.toFile().text
        String[] stderrLines = stderrContent.split('\n')
        String firstLines = stderrLines.take(10).join('\n')
        String lastLines = stderrLines.takeRight(10).join('\n')
        log.error(errMsg + "\n" +
                    "Command: ${command.join(' ')} exited with exit code ${process.exitValue()}\n" +
                    "First 10 lines of stderr:\n$firstLines\n" +
                    "Last 10 lines of stderr:\n$lastLines\n" +
                    "Check the log files in:\n" + 
                    "\t'${execDir.stdout.toAbsolutePath()}'\n" +
                    "\t'${execDir.stderr.toAbsolutePath()}'")
        throw new nextflow.exception.ProcessEvalException(errMsg, command.join(' '), '', process.exitValue())
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

    private String getPythonExecutable(String condaEnv) {
        CondaCache cache = new CondaCache(session.getCondaConfig())

        log.debug "Looking for conda env '$condaEnv' in conda cache"
        java.nio.file.Path condaPath = cache.getCachePathFor(condaEnv)
        log.debug "Conda environment found in '$condaPath'"

        // We can't just use "$condaPath/bin/python" because conda may not have python installed.
        // TODO: Should we cache this?
        Process proc = new ProcessBuilder('conda', 'run', '-p', condaPath.toString(), 'which', 'python')
            .redirectErrorStream(true)
            .start()
        def output = proc.inputStream.text.trim()
        if (!proc.waitFor() == 0 || !output) {
            throw new IllegalStateException("Failed to find Python executable in conda environment: $condaEnv\n Output: $output")
        }

        log.debug "Found Python executable in conda environment: $output"
        return output
    }

    private File workDirForHash(String hash) {
        def root = session.getWorkDir().resolve('.nf-python')
        def path = root.resolve(hash.substring(0, 2)).resolve(hash.substring(2))
        return path.toFile()
    }

}
